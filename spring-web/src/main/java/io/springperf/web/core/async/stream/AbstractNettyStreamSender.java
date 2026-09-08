package io.springperf.web.core.async.stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.shaded.org.jctools.queues.MpscArrayQueue;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.NettyServerHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Netty 流式发送器的抽象基类。
 * <p>
 * 提供公共的构造器、校验、完成、调度、drain 后置处理等逻辑。
 * 子类只需实现 {@link #send(Object)} 和 {@link #drain()} 两个方法。
 *
 * @see DefaultNettyStreamSender  EventLoop 延迟编码（默认）
 * @see EarlyEncodeNettyStreamSender  App 线程早编码
 */
public abstract class AbstractNettyStreamSender implements StreamSender {

    protected static final Logger log = LoggerFactory.getLogger(AbstractNettyStreamSender.class);

    protected static final int MAX_QUEUED_EVENTS = 65536;

    protected final Channel channel;
    protected final EventExecutor eventLoop;
    protected final ByteBufAllocator bufAllocator;
    protected final StreamEmitter emitter;
    protected final NettyServerHttpResponse resp;
    protected final MpscArrayQueue<Object> queue;

    protected final int maxFlushBytes;

    protected final AtomicInteger wip = new AtomicInteger(0);
    protected volatile boolean completed;

    protected boolean lastHttpContentWritten;

    protected volatile boolean closeChannelOnComplete = false;
    protected final ChannelFutureListener completeListener = future -> {
        if (future.isSuccess()) {
            onCompleteSuccess();
        } else {
            onCompleteError(future.cause());
        }
    };

    public AbstractNettyStreamSender(StreamEmitter emitter, PerfAsyncWebRequest asyncWebRequest) {
        this.emitter = emitter;
        this.resp = (NettyServerHttpResponse) asyncWebRequest.getNativeResponse();
        ChannelHandlerContext ctx = this.resp.getCtx();
        this.channel = ctx.channel();
        this.eventLoop = ctx.executor();
        this.bufAllocator = ctx.alloc();
        this.queue = new MpscArrayQueue<>(MAX_QUEUED_EVENTS);
        this.maxFlushBytes = emitter.getMaxFlushBytes();
        this.resp.setWritableCallback(this::scheduleDrain);
    }

    protected final void preSendCheck() throws IOException {
        if (!channel.isActive()) {
            throw new IOException("Stream closed");
        }
        if (completed) {
            throw new IOException("Stream completed");
        }
    }

    @Override
    public void complete(boolean closeChannelOnComplete, Throwable failure) {
        this.closeChannelOnComplete = closeChannelOnComplete;
        this.completed = true;
        scheduleDrain();
    }

    @Override
    public int queueSize() {
        return queue.size();
    }

    @Override
    public void send(Object data) throws IOException {
        preSendCheck();
        enqueueWithBackpressure(data);
        scheduleDrain();
    }

    /**
     * 批量入队并仅调度一次 drain。
     * <p>
     * 与逐条 {@link #send(Object)} 的区别：每条 send 的 scheduleDrain 在
     * EventLoop 线程上会同步 drain 并立即 flush 当前队列（只有刚入队的一条），
     * 破坏 {@code drain()} 里 batchBuf + maxFlushBytes 的批量编码设计；
     * 批量入队后 drain 一次即可编码整个批次。
     */
    @Override
    public void sendAll(Collection<?> data) throws IOException {
        if (data.isEmpty()) {
            return;
        }
        preSendCheck();
        for (Object item : data) {
            enqueueWithBackpressure(item);
        }
        scheduleDrain();
    }

    /**
     * 入队并等待空位（背压自旋）。
     * <p>
     * 队列满时短暂自旋等待 drain 消费腾出空间；自旋中重复 {@link #preSendCheck()}
     * 以在 channel 关闭或流完成时立即失败，避免无限自旋。
     */
    private void enqueueWithBackpressure(Object data) throws IOException {
        for (int spins = 0; ; spins++) {
            if (queue.offer(data)) {
                return;
            }
            preSendCheck();
            if (spins < 10) {
                Thread.yield();
            } else {
                LockSupport.parkNanos(1000);
            }
        }
    }

    /**
     * 批量写出一个 HttpContent 帧并挂写完成监听器（isComplete=false）：
     * 写成功 → writeStreamSuccessCallback → asyncWebRequest 的写回调（背压补充订阅请求）；
     * 写失败 → writeStreamErrorCallback → 终止流。修复前 drain 直写 channel 不挂监听器，
     * 遵守背压的冷 Publisher 在 highWaterMark 条后永不再被补充请求，流静默停滞。
     */
    protected void flushContent(ByteBuf buf) {
        ChannelFuture f = channel.writeAndFlush(new DefaultHttpContent(buf));
        resp.addRespEventListener(f, false);
    }

    protected void scheduleDrain() {
        // 统一走 wip 计数：仅当无 drain 在执行（wip 从 0 递增）才真正调用/调度 drain。
        // 修复前 inEventLoop 分支无条件直接 drain()——drain 执行中 flushContent 的写完成
        // 回调（同步 Publisher → request → onNext → send）重入时又直接调 drain()，
        // 递归深度随同步元素数增长，Flux.range(1, N) 长流 StackOverflowError。
        // 现在重入请求只递增 wip，由当前 drain 末尾 missed 循环消化，继续排空新数据。
        if (wip.getAndIncrement() == 0) {
            if (eventLoop.inEventLoop()) {
                drain();
            } else {
                eventLoop.execute(this::drain);
            }
        }
    }

    /**
     * 子类实现 drain 逻辑，末尾必须调用 {@link #afterDrain()}。
     */
    protected abstract void drain();

    /**
     * drain 末尾的 re-drain 检查。子类 drain 方法末尾调用。
     * <ul>
     *   <li>队列非空 → 尝试重新调度 drain</li>
     *   <li>已 completed 且未写 LastHttpContent → 直接写入关闭连接</li>
     * </ul>
     */
    protected void afterDrain() {
        if (!queue.isEmpty()) {
            // 队列仍有数据：channel 可写则继续 drain。
            // channel 不可写或 reschedule 失败（wip 已被其他线程设置）时什么都不做：
            // 依赖 BackpressureHandler 的 writable callback（不可写 -> 可写）触发
            // scheduleDrain() 恢复排空。严禁丢弃队列数据或提前结束流，否则会截断 SSE 响应。
            if (channel.isWritable() && wip.compareAndSet(0, 1)) {
                eventLoop.execute(this::drain);
            }
            return;
        }
        if (completed && !lastHttpContentWritten) {
            lastHttpContentWritten = true;
            onAllDataWritten();
        }
    }

    protected void onAllDataWritten() {
        this.resp.setWritableCallback(null);
        ChannelFuture f = channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        resp.addRespEventListener(f, true);
        f.addListener(completeListener);
    }

    protected void onCompleteSuccess() {
        this.resp.setTimeout(null, -1);
        if (closeChannelOnComplete || !resp.isKeepAlive()) {
            this.channel.close();
        }
    }

    protected void onCompleteError(Throwable t) {
        this.resp.setTimeout(null, -1);
        log.warn("[SSE] write ERROR: {}", t.getMessage(), t);
        if (closeChannelOnComplete || !resp.isKeepAlive()) {
            this.channel.close();
        }
    }
}