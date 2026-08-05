package io.springperf.web.core.async.stream;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.shaded.org.jctools.queues.MpscArrayQueue;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.NettyServerHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

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

    protected void scheduleDrain() {
        if (eventLoop.inEventLoop()) {
            drain();
        } else if (wip.getAndIncrement() == 0) {
            eventLoop.execute(this::drain);
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
            if (channel.isWritable() && wip.compareAndSet(0, 1)) {
                eventLoop.execute(this::drain);
                return;
            }
            // 队列非空但 channel 不可写或 reschedule 失败（wip 已被其他线程设置）：
            // 若已 completed 则直接关闭连接，防止 LastHttpContent 永不写入导致连接挂起。
            // 队列中剩余数据将被丢弃（send 端已 completed，不会再生产数据）。
            if (completed && !lastHttpContentWritten) {
                lastHttpContentWritten = true;
                onAllDataWritten();
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