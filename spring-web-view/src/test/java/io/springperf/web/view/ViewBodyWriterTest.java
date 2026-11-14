package io.springperf.web.view;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewBodyWriterTest {

    private static final Charset GBK = Charset.forName("GBK");
    private static final Charset UTF_16BE = StandardCharsets.UTF_16BE;
    private static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;

    /** 中文 + 代理对（emoji）：覆盖 2/3/4 字节编码与跨块拼接。 */
    private static final String SAMPLE = "<h1>中文标题</h1><p>item 😀👍 100161JR01311</p>";

    @Test
    void write_utf8_matchesStringBytes() throws IOException {
        byte[] actual = writeFast(SAMPLE, StandardCharsets.UTF_8, SAMPLE.length(), ViewBodyWriter.CHAR_BUFFER_SIZE,
                false);

        assertArrayEquals(SAMPLE.getBytes(StandardCharsets.UTF_8), actual);
    }

    @Test
    void write_charByChar_acrossTinyBuffer_matchesJdk() throws IOException {
        // 缓冲只有 3 个字符：代理对必然被拆到两个块里
        byte[] actual = writeFast(SAMPLE, StandardCharsets.UTF_8, 1, 3, false);
        byte[] expected = writeJdk(SAMPLE, StandardCharsets.UTF_8, 1, false);

        assertArrayEquals(expected, actual);
    }

    @Test
    void write_everySplitPoint_producesSameBytesAsJdk() throws IOException {
        String text = "ab😀中\ud83d文👍z";
        for (int split = 0; split <= text.length(); split++) {
            byte[] actual = writeFast(text, StandardCharsets.UTF_8, split, 3, true);
            byte[] expected = writeJdk(text, StandardCharsets.UTF_8, split, true);
            assertArrayEquals(expected, actual, "split=" + split);
        }
    }

    @Test
    void write_loneSurrogate_replacedLikeJdk() throws IOException {
        String text = "a\ud83db";

        byte[] actual = writeFast(text, StandardCharsets.UTF_8, 1, 3, false);

        assertArrayEquals(writeJdk(text, StandardCharsets.UTF_8, 1, false), actual);
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), actual, "应与 String#getBytes 的 REPLACE 行为一致");
    }

    @Test
    void write_nonUtf8Charsets_matchJdk() throws IOException {
        Charset[] charsets = { StandardCharsets.UTF_8, UTF_16BE, StandardCharsets.UTF_16LE, ISO_8859_1, GBK };
        for (Charset charset : charsets) {
            // ISO-8859-1 下中文属不可映射字符，走 REPLACE 分支
            byte[] actual = writeFast(SAMPLE, charset, 2, 5, false);
            byte[] expected = writeJdk(SAMPLE, charset, 2, false);
            assertArrayEquals(expected, actual, "charset=" + charset.name());
        }
    }

    @Test
    void flush_betweenSurrogates_keepsPendingChar() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer writer = new ViewBodyWriter(out, StandardCharsets.UTF_8, 3)) {
            writer.write("a\ud83d");
            writer.flush();
            writer.write("\ude00b");
        }

        ByteArrayOutputStream jdkOut = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(jdkOut, StandardCharsets.UTF_8)) {
            writer.write("a\ud83d");
            writer.flush();
            writer.write("\ude00b");
        }

        assertArrayEquals(jdkOut.toByteArray(), out.toByteArray());
        assertEquals("a😀b", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void close_flushesRemainingTextWithoutExplicitFlush() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Writer writer = ViewBodyWriter.wrap(out, StandardCharsets.UTF_8);
        writer.write("未 flush 的内容");
        writer.close();

        assertEquals("未 flush 的内容", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void close_closesUnderlyingStream() throws IOException {
        TrackingStream out = new TrackingStream();
        ViewBodyWriter.wrap(out, StandardCharsets.UTF_8).close();

        assertTrue(out.closed, "close 必须关闭底层流，与 OutputStreamWriter 语义一致");
    }

    @Test
    void writeAfterClose_throwsIOException() throws IOException {
        Writer writer = ViewBodyWriter.wrap(new ByteArrayOutputStream(), StandardCharsets.UTF_8);
        writer.close();

        assertThrows(IOException.class, () -> writer.write("x"));
        assertThrows(IOException.class, () -> writer.write('x'));
        assertThrows(IOException.class, writer::flush);
    }

    @Test
    void writeInt_writesSingleChar() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer writer = ViewBodyWriter.wrap(out, StandardCharsets.UTF_8)) {
            writer.write('a');
            writer.write('中');
        }

        assertEquals("a中", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void write_emptyRange_writesNothing() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer writer = ViewBodyWriter.wrap(out, StandardCharsets.UTF_8)) {
            writer.write(new char[0], 0, 0);
            writer.write("", 0, 0);
        }

        assertEquals(0, out.size());
    }

    @Test
    void write_onlyRequestedRange() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer writer = ViewBodyWriter.wrap(out, StandardCharsets.UTF_8)) {
            writer.write("__body__".toCharArray(), 2, 4);
            writer.write("xxvaluexx", 2, 5);
        }

        assertEquals("bodyvalue", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void write_invalidRange_throwsIndexOutOfBounds() {
        Writer writer = ViewBodyWriter.wrap(new ByteArrayOutputStream(), StandardCharsets.UTF_8);

        assertThrows(IndexOutOfBoundsException.class, () -> writer.write(new char[2], 1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> writer.write("ab", 2, 1));
    }

    @Test
    void largePayload_acrossManyBufferOverflows_matchesJdk() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("第").append(i).append("行：碳化硅衬底 😀\n");
        }
        String text = sb.toString();

        byte[] actual = writeFast(text, StandardCharsets.UTF_8, 97, 7, false);

        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), actual);
    }

    @Test
    void constructor_rejectsTooSmallBuffer() {
        assertThrows(IllegalArgumentException.class,
                () -> new ViewBodyWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8, 1));
        assertThrows(NullPointerException.class, () -> new ViewBodyWriter(null, StandardCharsets.UTF_8, 4));
    }

    /** 用指定块大小写入，{@code chunk} 为 0 表示整串一次写入。 */
    private static byte[] writeFast(String text, Charset charset, int chunk, int bufferSize, boolean flushEachChunk)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer writer = new ViewBodyWriter(out, charset, bufferSize)) {
            writeInChunks(writer, text, chunk, flushEachChunk);
        }
        return out.toByteArray();
    }

    private static byte[] writeJdk(String text, Charset charset, int chunk, boolean flushEachChunk) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(out, charset)) {
            writeInChunks(writer, text, chunk, flushEachChunk);
        }
        return out.toByteArray();
    }

    private static void writeInChunks(Writer writer, String text, int chunk, boolean flushEachChunk)
            throws IOException {
        if (chunk <= 0) {
            writer.write(text);
            return;
        }
        for (int off = 0; off < text.length(); off += chunk) {
            int len = Math.min(chunk, text.length() - off);
            writer.write(text, off, len);
            if (flushEachChunk) {
                writer.flush();
            }
        }
    }

    private static final class TrackingStream extends OutputStream {

        private boolean closed;

        @Override
        public void write(int b) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
