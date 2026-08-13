package io.springperf.web.core.async.stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.springperf.web.core.async.PerfAsyncWebRequest;

/**
 * 早编码流式发送器（App 线程编码）。
 * <p>
 * 接收已编码的 byte[]，App 线程仅入队 byte[]，零 ByteBuf 分配。
 * EventLoop 线程的 {@link #drain()} 分配 pooled batchBuf 将多个 byte[] 拷贝合并为
 * 单个 {@link DefaultHttpContent} 写入 channel，减少 pipeline 对象数。
 * <p>
 * 与 {@link DefaultNettyStreamSender} 的区别：后者在 drain 中调用 {@code emitter.encode()}
 * 编码原始数据，而本类接收的已是编码后的 byte[]，直接拷贝。
 * <p>
 * 对应 {@link StreamEmitter#isEarlyEncode()} = {@code true} 的路径。
 */
public class EarlyEncodeNettyStreamSender extends AbstractNettyStreamSender {

    public EarlyEncodeNettyStreamSender(StreamEmitter emitter, PerfAsyncWebRequest asyncWebRequest) {
        super(emitter, asyncWebRequest);
    }

    /**
     * 只能在 EventLoop 线程执行。
     * 分配 batchBuf 批量从队列取出 byte[] 拷贝，超过 maxFlushBytes 后 write + flush。
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
            while (channel.isWritable()) {
                Object data = queue.poll();
                if (data == null) {
                    break;
                }
                byte[] bytes = (byte[]) data;
                if (bytes.length == 0) {
                    continue;
                }
                if (bytes.length > maxFlushBytes) {
                    // 单条消息超过 batch 容量：先刷掉已有批数据，再直接独立写入，
                    // 避免 writeBytes 超出 batchBuf 容量抛 IndexOutOfBoundsException。
                    if (batchBuf != null) {
                        flushContent(batchBuf);
                        batchBuf = null;
                    }
                    flushContent(Unpooled.wrappedBuffer(bytes));
                    continue;
                }
                if (batchBuf == null) {
                    batchBuf = bufAllocator.buffer(maxFlushBytes);
                }
                if (batchBuf.writableBytes() < bytes.length) {
                    flushContent(batchBuf);
                    batchBuf = bufAllocator.buffer(maxFlushBytes);
                }
                batchBuf.writeBytes(bytes);
            }
            if (batchBuf != null) {
                if (batchBuf.readableBytes() > 0) {
                    flushContent(batchBuf);
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