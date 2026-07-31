package io.springperf.web.core.async.stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.springperf.web.core.async.PerfAsyncWebRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

/**
 * {@link NettyStreamSender} 的字符串实现，队列存放 {@link CharSequence}。
 * 走 {@link StreamEmitter#encodeToString(Object)} 路径。
 */
public class StringNettyStreamSender extends NettyStreamSender<CharSequence> {

    public StringNettyStreamSender(StreamEmitter emitter, PerfAsyncWebRequest asyncWebRequest) {
        super(emitter, asyncWebRequest);
    }

    @Override
    public void send(Object data) throws IOException {
        preSendCheck();
        CharSequence cs = emitter.encodeToString(data);
        if (cs == null || cs.length() == 0) {
            return;
        }
        if (!queue.offer(cs)) {
            backpressureWait(cs);
            return;
        }
        scheduleDrain();
    }

    @Override
    protected void drainWrite(ByteBuf buf, CharSequence item) {
        writeCharSequence(buf, item);
    }

    protected void writeCharSequence(ByteBuf buf, CharSequence charSequence) {
        Charset charset = resp.getCharacterEncoding();
        if (StandardCharsets.UTF_8.equals(charset)) {
            ByteBufUtil.writeUtf8(buf, charSequence);
        } else if (StandardCharsets.US_ASCII.equals(charset)) {
            ByteBufUtil.writeAscii(buf, charSequence);
        } else {
            CharsetEncoder charsetEncoder = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            CharBuffer inBuffer = CharBuffer.wrap(charSequence);
            int estimatedSize = (int) (inBuffer.remaining() * charsetEncoder.averageBytesPerChar());
            ByteBuffer outBuffer = buf.ensureWritable(estimatedSize).nioBuffer(buf.writerIndex(), buf.writableBytes());
            while (true) {
                CoderResult cr = (inBuffer.hasRemaining() ? charsetEncoder.encode(inBuffer, outBuffer, true) : CoderResult.UNDERFLOW);
                if (cr.isUnderflow()) {
                    cr = charsetEncoder.flush(outBuffer);
                }
                if (cr.isUnderflow()) {
                    break;
                }
                if (cr.isOverflow()) {
                    buf.writerIndex(buf.writerIndex() + outBuffer.position());
                    int maximumSize = (int) (inBuffer.remaining() * charsetEncoder.maxBytesPerChar());
                    buf.ensureWritable(maximumSize);
                    outBuffer = buf.nioBuffer(buf.writerIndex(), buf.writableBytes());
                }
            }
            buf.writerIndex(buf.writerIndex() + outBuffer.position());
        }
    }
}