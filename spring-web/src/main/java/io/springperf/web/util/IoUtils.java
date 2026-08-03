package io.springperf.web.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

public class IoUtils {

    public static void writeCharSequence(OutputStream out, CharSequence charSequence, Charset charset) throws IOException {
        if (out instanceof ByteBufOutputStream) {
            writeCharSequence(((ByteBufOutputStream) out).buffer(), charSequence, charset);
        } else {
            out.write(charSequence.toString().getBytes(charset));
        }
    }

    public static void writeCharSequence(ByteBuf buf, CharSequence charSequence, Charset charset) {
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
