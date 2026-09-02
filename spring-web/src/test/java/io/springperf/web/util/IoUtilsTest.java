package io.springperf.web.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IoUtilsTest {

    @Test
    void writeCharSequence_utf8_byteBuf() {
        ByteBuf buf = Unpooled.buffer();
        IoUtils.writeCharSequence(buf, "hello-世界", StandardCharsets.UTF_8);
        String result = buf.toString(StandardCharsets.UTF_8);
        assertEquals("hello-世界", result);
        buf.release();
    }

    @Test
    void writeCharSequence_ascii_byteBuf() {
        ByteBuf buf = Unpooled.buffer();
        IoUtils.writeCharSequence(buf, "hello", StandardCharsets.US_ASCII);
        assertEquals("hello", buf.toString(StandardCharsets.US_ASCII));
        buf.release();
    }

    @Test
    void writeCharSequence_otherCharset_byteBuf() {
        ByteBuf buf = Unpooled.buffer();
        IoUtils.writeCharSequence(buf, "héllo", StandardCharsets.ISO_8859_1);
        assertEquals("héllo", buf.toString(StandardCharsets.ISO_8859_1));
        buf.release();
    }

    @Test
    void writeCharSequence_charsetWithSurrogateChars() {
        // UTF-16 编码，验证非 UTF-8/ASCII 分支的多字节与溢出处理
        ByteBuf buf = Unpooled.buffer();
        String input = "a\uD83D\uDE00b"; // 'a' + emoji + 'b'
        IoUtils.writeCharSequence(buf, input, StandardCharsets.UTF_16);
        assertEquals(input, buf.toString(StandardCharsets.UTF_16));
        buf.release();
    }

    @Test
    void writeCharSequence_emptyString() {
        ByteBuf buf = Unpooled.buffer();
        IoUtils.writeCharSequence(buf, "", StandardCharsets.UTF_8);
        assertEquals(0, buf.readableBytes());
        buf.release();
    }

    @Test
    void writeCharSequence_byteBufOutputStream() throws Exception {
        ByteBuf buf = Unpooled.buffer();
        ByteBufOutputStream out = new ByteBufOutputStream(buf);
        IoUtils.writeCharSequence(out, "hello", StandardCharsets.UTF_8);
        out.close();
        assertEquals("hello", buf.toString(StandardCharsets.UTF_8));
        buf.release();
    }

    @Test
    void writeCharSequence_plainOutputStream() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IoUtils.writeCharSequence(out, "hello-世界", StandardCharsets.UTF_8);
        assertEquals("hello-世界", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void writeCharSequence_plainOutputStream_iso88591() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IoUtils.writeCharSequence(out, "héllo", Charset.forName("ISO-8859-1"));
        assertEquals("héllo", new String(out.toByteArray(), Charset.forName("ISO-8859-1")));
    }
}
