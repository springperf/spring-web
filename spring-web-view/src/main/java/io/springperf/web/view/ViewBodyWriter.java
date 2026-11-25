package io.springperf.web.view;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;

/**
 * 视图输出用的编码 Writer：按固定大小的小块把字符编码后直接写入响应体。
 * <p>
 * 为什么不直接用 {@link java.io.OutputStreamWriter}：JDK 的 {@code StreamEncoder} 在构造时固定分配 8192 字节缓冲，几百字节的短模板也要为此付出整块分配。实测每次渲染
 * {@code new OutputStreamWriter(resp.getBody(), charset)} 约分配 8.6~15 KB，是整条视图链路里最大的一笔分配（远大于适配器、model 合并等合计开销）。
 * </p>
 * <p>
 * 本实现用 {@value #CHAR_BUFFER_SIZE} 字符的暂存块分块编码，每请求约 0.9 KB，且编码结果直接写入响应体，省掉 {@code StreamEncoder} 的整页中转。
 * </p>
 * <p>
 * 语义与 {@code OutputStreamWriter} 对齐：编码错误按 {@link CodingErrorAction#REPLACE} 处理（与 {@code String#getBytes(Charset)}
 * 一致，不会因为模板里出现不可映射字符而抛异常）；{@code close()} 完成尾部编码并关闭底层流。 非线程安全，每次渲染新建一个。
 * </p>
 *
 * @since 2.7.8
 */
public final class ViewBodyWriter extends Writer {

    /** 字符暂存块大小。实测 128 是「分配」与「编码调用次数」的平衡点：64 太碎、256 分配翻倍但耗时几乎不变。 */
    static final int CHAR_BUFFER_SIZE = 128;

    /** 字节块上限：适配 UTF-32 等宽字符集，同时避免异常编码器声明超大 maxBytesPerChar 时分配膨胀。 */
    private static final int MAX_BYTE_BUFFER_SIZE = 4096;

    private final OutputStream out;
    private final CharsetEncoder encoder;
    /** 复用同一块字符暂存区，避免每次 write 都包装一个新的 CharBuffer。 */
    private final CharBuffer chars;
    private final ByteBuffer bytes;
    private boolean closed;

    /**
     * 生产入口：使用默认块大小。
     *
     * @param out
     *            目标输出流（通常是 {@code resp.getBody()}）
     * @param charset
     *            响应编码
     *
     * @return 每次渲染独立的 Writer，用 try-with-resources 关闭
     */
    public static Writer wrap(OutputStream out, Charset charset) {
        return new ViewBodyWriter(out, charset, CHAR_BUFFER_SIZE);
    }

    /** 包可见：测试用小缓冲强制跨越边界，验证代理对与溢出路径。 */
    ViewBodyWriter(OutputStream out, Charset charset, int charBufferSize) {
        this.out = Objects.requireNonNull(out, "out");
        Objects.requireNonNull(charset, "charset");
        // 至少 2：编码器最多残留 1 个「待补全的代理字符」，暂存区小于 2 会让写入方没有可用空间
        if (charBufferSize < 2) {
            throw new IllegalArgumentException("charBufferSize must be at least 2: " + charBufferSize);
        }
        this.encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        this.chars = CharBuffer.allocate(charBufferSize);
        this.bytes = ByteBuffer.allocate(byteBufferSize(charBufferSize, encoder));
    }

    /**
     * 字节块按「一个字符块最多编码出多少字节」估算，避免宽字符集频繁触发 OVERFLOW 重试。
     */
    private static int byteBufferSize(int charBufferSize, CharsetEncoder encoder) {
        long needed = (long) Math.ceil(charBufferSize * (double) encoder.maxBytesPerChar());
        return (int) Math.min(MAX_BYTE_BUFFER_SIZE, Math.max(64L, needed));
    }

    @Override
    public void write(int c) throws IOException {
        ensureOpen();
        if (!chars.hasRemaining()) {
            flushChars(false);
        }
        chars.put((char) c);
    }

    /** JDK 8 兼容的范围检查（等价 {@code Objects.checkFromIndexSize}，后者自 JDK 9 起才有）。 */
    private static void checkRange(int off, int len, int size) {
        if (off < 0 || len < 0 || off > size - len) {
            throw new IndexOutOfBoundsException("off=" + off + ", len=" + len + ", size=" + size);
        }
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        checkRange(off, len, cbuf.length);
        ensureOpen();
        while (len > 0) {
            int n = Math.min(len, chars.remaining());
            chars.put(cbuf, off, n);
            off += n;
            len -= n;
            if (!chars.hasRemaining()) {
                flushChars(false);
            }
        }
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        checkRange(off, len, str.length());
        ensureOpen();
        while (len > 0) {
            int n = Math.min(len, chars.remaining());
            str.getChars(off, off + n, chars.array(), chars.arrayOffset() + chars.position());
            chars.position(chars.position() + n);
            off += n;
            len -= n;
            if (!chars.hasRemaining()) {
                flushChars(false);
            }
        }
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        flushChars(false);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flushChars(true);
            out.flush();
        } finally {
            out.close();
        }
    }

    /**
     * 把暂存区里的字符编码写出。
     * <p>
     * {@code endOfInput=false} 时编码器可能留下「半个代理对」而不消费它（等下一个 char 补全），因此剩余字符必须 {@link CharBuffer#compact() compact}
     * 回暂存区开头，直接 clear 会丢字符——这一点与 {@code StreamEncoder} 内部处理一致。
     * </p>
     */
    private void flushChars(boolean endOfInput) throws IOException {
        chars.flip();
        pump(chars, endOfInput);
        if (!endOfInput && chars.hasRemaining()) {
            chars.compact();
        } else {
            chars.clear();
        }
        drainBytes();
    }

    private void pump(CharBuffer in, boolean endOfInput) throws IOException {
        while (true) {
            CoderResult result = encoder.encode(in, bytes, endOfInput);
            if (result.isOverflow()) {
                drainBytes();
                continue;
            }
            if (result.isUnderflow()) {
                return;
            }
            // 已配置 REPLACE，正常不会走到这里；保留以覆盖自定义 Charset 实现返回错误的情况
            result.throwException();
        }
    }

    private void drainBytes() throws IOException {
        bytes.flip();
        if (bytes.hasRemaining()) {
            out.write(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
        }
        bytes.clear();
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }
}
