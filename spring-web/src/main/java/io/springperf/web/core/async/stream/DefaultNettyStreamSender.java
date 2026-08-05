package io.springperf.web.core.async.stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.springperf.web.core.async.PerfAsyncWebRequest;

import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

/**
 * 延迟编码流式发送器（EventLoop 线程编码，默认实现）。
 * <p>
 * App 线程仅入队原始数据，零 ByteBuf 分配。
 * EventLoop 线程的 {@link #drain()} 分配 batchBuf 批量编码写入 channel，
 * 避免 App 线程多线程竞争 PoolArena 锁。
 * <p>
 * 对应 {@link StreamEmitter#isEarlyEncode()} = {@code false} 的路径。
 */
public class DefaultNettyStreamSender extends AbstractNettyStreamSender {

    public DefaultNettyStreamSender(StreamEmitter emitter, PerfAsyncWebRequest asyncWebRequest) {
        super(emitter, asyncWebRequest);
    }

    @Override
    public void send(Object data) throws IOException {
        preSendCheck();
        for (int spins = 0; ; spins++) {
            if (queue.offer(data)) {
                scheduleDrain();
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
     * 只能在 EventLoop 线程执行。
     * 分配 batchBuf 批量从队列中取出原始数据编码写入，超过 maxFlushBytes 后 flush。
     */
    @Override
    protected void drain() {
        if (!channel.isActive()) {
            queue.clear();
            if (completed && !lastHttpContentWritten) {
                lastHttpContentWritten = true;
                onAllDataWritten();
            }
            return;
        }
        int missed = 1;
        for (; ; ) {
            ByteBuf batchBuf = null;
            ByteBufOutputStream batchOut = null;
            while (channel.isWritable()) {
                Object data = queue.poll();
                if (data == null) {
                    break;
                }
                if (batchBuf == null) {
                    batchBuf = bufAllocator.buffer(maxFlushBytes);
                    batchOut = new ByteBufOutputStream(batchBuf);
                }
                int before = batchBuf.writerIndex();
                try {
                    emitter.encode(data, batchOut);
                } catch (Exception e) {
                    batchBuf.writerIndex(before);
                    log.warn("[SSE] encode error: {}", e.getMessage(), e);
                    emitter.onEncodeError(data, e);
                }
                if (batchBuf.writerIndex() >= maxFlushBytes) {
                    channel.writeAndFlush(new DefaultHttpContent(batchBuf));
                    batchBuf = null;
                    batchOut = null;
                }
            }
            if (batchBuf != null) {
                if (batchBuf.readableBytes() > 0) {
                    channel.writeAndFlush(new DefaultHttpContent(batchBuf));
                } else {
                    batchBuf.release();
                }
            }
            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                break;
            }
        }
        afterDrain();
    }
}