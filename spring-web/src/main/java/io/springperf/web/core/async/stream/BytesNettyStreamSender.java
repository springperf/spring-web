package io.springperf.web.core.async.stream;

import io.netty.buffer.ByteBuf;
import io.springperf.web.core.async.PerfAsyncWebRequest;

import java.io.IOException;

/**
 * {@link NettyStreamSender} 的字节数组实现，队列存放 {@code byte[]}。
 * 走 {@link StreamEmitter#encodeToBytes(Object)} 路径。
 */
public class BytesNettyStreamSender extends NettyStreamSender<byte[]> {

    public BytesNettyStreamSender(StreamEmitter emitter, PerfAsyncWebRequest asyncWebRequest) {
        super(emitter, asyncWebRequest);
    }

    @Override
    public void send(Object data) throws IOException {
        preSendCheck();
        byte[] bytes = emitter.encodeToBytes(data);
        if (bytes == null || bytes.length == 0) {
            return;
        }
        if (!queue.offer(bytes)) {
            backpressureWait(bytes);
            return;
        }
        scheduleDrain();
    }

    @Override
    protected void drainWrite(ByteBuf buf, byte[] item) {
        buf.writeBytes(item);
    }
}