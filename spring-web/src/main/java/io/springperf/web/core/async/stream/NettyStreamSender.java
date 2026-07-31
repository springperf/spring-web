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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * 异步流式发送器的抽象基类。
 * <p>
 * 子类决定队列中存放的数据类型：
 * <ul>
 *   <li>{@link StringNettyStreamSender} — 队列 {@code CharSequence}，走 {@code encodeToString} 路径</li>
 *   <li>{@link BytesNettyStreamSender} — 队列 {@code byte[]}，走 {@code encodeToBytes} 路径</li>
 * </ul>
 * 编码在 {@link #send(Object)} 中完成，入队不可变数据，消除外部突变风险。
 * {@link #drain()} 在 EventLoop 线程批量写入 ByteBuf，避免跨线程 ByteBuf 缓存。
 *
 * @param <T> 队列元素类型，{@code byte[]} 或 {@code CharSequence}
 */
public abstract class NettyStreamSender<T> implements StreamSender {

    protected static final Logger log = LoggerFactory.getLogger(NettyStreamSender.class);

    private static final int MAX_QUEUED_EVENTS = 65536;

    protected final Channel channel;
    protected final EventExecutor eventLoop;
    protected final ByteBufAllocator bufAllocator;
    protected final StreamEmitter emitter;
    protected final NettyServerHttpResponse resp;
    protected final MpscArrayQueue<T> queue;

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

    public NettyStreamSender(StreamEmitter emitter, PerfAsyncWebRequest asyncWebRequest) {
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

    /**
     * 子类 {@link #send(Object)} 前置检查：channel 状态和 completed 标志。
     */
    protected final void preSendCheck() throws IOException {
        if (!channel.isActive()) {
            throw new IOException("Stream closed");
        }
        if (completed) {
            throw new IOException("Stream completed");
        }
    }

    /**
     * 将编码后的数据项写入 ByteBuf（由子类实现，无需 instanceof 判断）。
     */
    protected abstract void drainWrite(ByteBuf buf, T item);

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

    /**
     * 入队背压等待，由子类 {@link #send(Object)} 调用。
     */
    protected void backpressureWait(T item) throws IOException {
        int spins = 0;
        while (!queue.offer(item)) {
            preSendCheck();
            if (spins++ < 10) {
                Thread.yield();
            } else {
                LockSupport.parkNanos(1000);
            }
        }
        scheduleDrain();
    }

    protected void scheduleDrain() {
        if (eventLoop.inEventLoop()) {
            drain();
        } else if (wip.getAndIncrement() == 0) {
            eventLoop.execute(this::drain);
        }
    }

    /**
     * 只能在 EventLoop 线程执行。
     * 从队列取出已编码数据，批量写入 ByteBuf 后 flush。
     * 依赖 batchBuf.writableBytes() 自然切分 HTTP chunk，无需 writtenBytes 阈值。
     */
    protected void drain() {
        if (!channel.isActive()) {
            T remaining;
            while ((remaining = queue.poll()) != null) {
                // channel 已关闭，直接丢弃
            }
            if (completed && !lastHttpContentWritten) {
                lastHttpContentWritten = true;
                onAllDataWritten();
            }
            return;
        }
        int missed = 1;
        for (; ; ) {
            boolean flushed = false;
            ByteBuf batchBuf = null;

            while (channel.isWritable()) {
                T item = queue.poll();
                if (item == null) {
                    break;
                }
                try {
                    if (batchBuf == null) {
                        batchBuf = bufAllocator.buffer(maxFlushBytes);
                    }
                    drainWrite(batchBuf, item);
                    flushed = true;

                    if (batchBuf.writerIndex() >= maxFlushBytes) {
                        ChannelFuture f = channel.write(new DefaultHttpContent(batchBuf));
                        resp.addRespEventListener(f, false);
                        batchBuf = null;
                    }
                } catch (Exception e) {
                    log.warn("[SSE] drain write error: {}", e.getMessage(), e);
                }
            }

            if (batchBuf != null && batchBuf.readableBytes() > 0) {
                ChannelFuture f = channel.write(new DefaultHttpContent(batchBuf));
                resp.addRespEventListener(f, false);
            }
            if (flushed) {
                channel.flush();
            }
            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                break;
            }
        }
        if (!queue.isEmpty()) {
            if (channel.isWritable() && wip.compareAndSet(0, 1)) {
                eventLoop.execute(this::drain);
            } else if (completed && !lastHttpContentWritten) {
                if (wip.compareAndSet(0, 1)) {
                    eventLoop.execute(this::drain);
                }
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