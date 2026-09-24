package io.springperf.web.http.support;

import static io.springperf.web.context.PropertiesConstant.HTTP_MULTIPART_MAX_PART_HEADER_SIZE_DEFAULT;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.springperf.web.server.MultipartConfig;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SupportMultipartResolver {

    /**
     * HTTP 数据工厂：决定 part 是否落盘（尺寸阈值）与落盘目录。 默认对齐框架既有行为（Netty MINSIZE=16KB，系统临时目录）；可由
     * {@code spring.servlet.multipart.file-size-threshold} / {@code .location} 配置。
     */
    protected HttpDataFactory factory;

    /** 整个 multipart 请求体的最大字节数（-1 表示不限制）。 */
    private final long maxContentLength;
    /** multipart part 总数上限（≤0 表示不限制，默认 -1）。 */
    private final int maxPartCount;
    /**
     * 单 part header 区字节上限（≤0 表示不限制）。默认 8192 —— **与 Boot/Tomcat 的同名键不同**： {@code server.tomcat.max-part-header-size} 在
     * Boot 元数据里的默认值是 {@code 512B}，本项目为 8192。
     */
    private final int maxPartHeaderSize;
    /** 单个上传文件大小上限（字节，≤0 表示不限制）。对齐 spring.servlet.multipart.max-file-size。 */
    private final long maxFileSize;
    /** 已消费的字节数（流式累计，用于限制 chunked / 无 Content-Length 的请求）。 */
    private long consumedBytes;

    protected HttpRequest request;

    protected HttpHeaders trailingHeader = EmptyHttpHeaders.INSTANCE;
    protected HttpPostRequestDecoder decoder;
    boolean currentMultipart = false;

    // ---- 单 part header 增量扫描状态（B2：防恶意超长 part header 耗尽内存） ----
    /** 当前请求的 part 边界分隔符（"--boundary"），null 表示无法解析/非 multipart。 */
    private byte[] partBoundaryDelim;
    /** 是否正处于某个 part 的 header 区（介于边界行与终止空行之间）。 */
    private boolean inPartHeader;
    /** 当前 part header 区已累计字节数。 */
    private int partHeaderLen;
    /** 是否已遇到结束边界（--boundary--），无需再扫描。 */
    private boolean partHeaderScanDone;
    /** 跨 chunk 尾部留样，用于检测被分片拆断的边界/空行序列。 */
    private byte[] partHeaderCarry = EMPTY_CARRY;
    private static final byte[] EMPTY_CARRY = new byte[0];

    public SupportMultipartResolver() {
        this(-1, -1, HTTP_MULTIPART_MAX_PART_HEADER_SIZE_DEFAULT);
    }

    public SupportMultipartResolver(long maxContentLength) {
        this(maxContentLength, -1, HTTP_MULTIPART_MAX_PART_HEADER_SIZE_DEFAULT);
    }

    public SupportMultipartResolver(long maxContentLength, int maxPartCount) {
        this(maxContentLength, maxPartCount, HTTP_MULTIPART_MAX_PART_HEADER_SIZE_DEFAULT);
    }

    public SupportMultipartResolver(long maxContentLength, int maxPartCount, int maxPartHeaderSize) {
        this(maxContentLength, maxPartCount, maxPartHeaderSize, -1L);
    }

    public SupportMultipartResolver(long maxContentLength, int maxPartCount, int maxPartHeaderSize, long maxFileSize) {
        this(maxContentLength, maxPartCount, maxPartHeaderSize, maxFileSize, MultipartConfig.FILE_SIZE_THRESHOLD_UNSET,
                "");
    }

    /**
     * 完整构造：按 {@code spring.servlet.multipart.file-size-threshold} / {@code .location} 创建 HTTP 数据工厂。
     *
     * @param fileSizeThreshold
     *            part 落盘阈值（字节）；{@code <0}（{@link MultipartConfig#FILE_SIZE_THRESHOLD_UNSET}） 表示沿用框架默认（Netty
     *            MINSIZE=16KB）
     * @param location
     *            上传临时目录；空表示沿用 Netty 默认（{@code java.io.tmpdir}）
     */
    public SupportMultipartResolver(long maxContentLength, int maxPartCount, int maxPartHeaderSize, long maxFileSize,
            long fileSizeThreshold, String location) {
        this.maxContentLength = maxContentLength;
        this.maxPartCount = maxPartCount;
        this.maxPartHeaderSize = maxPartHeaderSize;
        this.maxFileSize = maxFileSize;
        this.factory = createFactory(fileSizeThreshold, location);
    }

    /** 创建 HTTP 数据工厂：阈值 <0 用 Netty MINSIZE，location 非空时设置基目录。 */
    private static HttpDataFactory createFactory(long fileSizeThreshold, String location) {
        long threshold = fileSizeThreshold < 0 ? DefaultHttpDataFactory.MINSIZE : fileSizeThreshold;
        DefaultHttpDataFactory dataFactory = new DefaultHttpDataFactory(threshold);
        if (location != null && !location.trim().isEmpty()) {
            dataFactory.setBaseDir(location.trim());
        }
        return dataFactory;
    }

    public boolean isMultipartMode() {
        return currentMultipart;
    }

    public boolean isMultipart(HttpRequest request) {
        return HttpPostRequestDecoder.isMultipart(request);
    }

    /** 当前正在处理的 multipart 请求头（供超限时构造 413 响应）。 */
    public HttpRequest getRequest() {
        return request;
    }

    public void start(HttpRequest request) {
        currentMultipart = true;
        this.request = request;
        this.consumedBytes = 0;
        // 复位单 part header 增量扫描状态（resolver 按连接复用，需逐请求重置）
        this.inPartHeader = false;
        this.partHeaderLen = 0;
        this.partHeaderScanDone = false;
        this.partHeaderCarry = EMPTY_CARRY;
        this.partBoundaryDelim = parseBoundary(request);
        // Content-Length 提前 fail-fast：声明长度超限直接拒绝，不进入流式消费
        long declared = HttpUtil.getContentLength(request, -1L);
        if (maxContentLength > 0 && declared > maxContentLength) {
            abort();
            throw new TooLongFrameException(
                    "multipart content length " + declared + " exceeds limit " + maxContentLength);
        }
        this.decoder = new HttpPostRequestDecoder(factory, request);
    }

    public void consume(HttpContent content) {
        // 流式累计字节数（覆盖 chunked / 无 Content-Length 的请求），超限抛异常拒绝
        if (maxContentLength > 0) {
            consumedBytes += content.content().readableBytes();
            if (consumedBytes > maxContentLength) {
                abort();
                throw new TooLongFrameException("multipart content exceeds limit " + maxContentLength);
            }
        }
        // B2：增量扫描每个 part 的 header 区字节数，超 server.http.multipart.max-part-header-size 抛
        // DecoderException（由 SupportMultipartAggregator 转 400）。保留流式消费/落盘优势，不缓存全量 body。
        if (maxPartHeaderSize > 0 && partBoundaryDelim != null && !partHeaderScanDone) {
            try {
                scanPartHeaders(content);
            } catch (DecoderException e) {
                // 扫描超限：content 尚未交给 decoder，必须手动释放避免 direct ByteBuf 泄漏
                try {
                    content.content().release();
                } catch (Exception ignored) {
                    // 已尽力释放
                }
                abort();
                throw e;
            }
        }
        if (content instanceof LastHttpContent) {
            trailingHeader = ((LastHttpContent) content).trailingHeaders();
        }
        try {
            decoder.offer(content);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            // 解码异常：本段 HttpContent 的 ByteBuf 未被 decoder 接管，必须手动释放，否则池化
            // direct ByteBuf 泄漏；同时销毁 decoder 释放其内部缓冲（undecodedChunk 等），并上抛终止
            // 本次 multipart 解码，避免调用方在 decoder 已置空后继续 finish() 触发 NPE。
            try {
                content.content().release();
            } catch (Exception ignored) {
                // 已尽力释放
            }
            abort();
            // 抛 DecoderException（而非裸 RuntimeException）：Netty 解码链路可识别其为解码失败，
            // 由 SupportMultipartAggregator 捕获并转换为 HTTP 400 优雅关闭，而非裸异常关闭连接。
            throw new DecoderException("multipart decode failed: " + e.getMessage(), e);
        }
    }

    public NettyMultipartWebRequest finish() {
        // B2 收尾：若最后一个 part 的 header 区尚未以空行结束且已超阈值，仍拒绝
        if (maxPartHeaderSize > 0 && inPartHeader && partHeaderLen > maxPartHeaderSize) {
            abort();
            throw new DecoderException(
                    "multipart part header size " + partHeaderLen + " exceeds limit " + maxPartHeaderSize);
        }
        // part 总数（file + attribute）超限：抛 DecoderException 由 SupportMultipartAggregator 转 400
        if (maxPartCount > 0) {
            List<InterfaceHttpData> datas = decoder.getBodyHttpDatas();
            if (datas.size() > maxPartCount) {
                abort();
                throw new DecoderException("multipart part count " + datas.size() + " exceeds limit " + maxPartCount);
            }
        }
        // spring.servlet.multipart.max-file-size：单个上传文件超限 → 413（TooLongFrameException
        // 由 SupportMultipartAggregator 转 413，与整体超限一致）。
        if (maxFileSize > 0) {
            for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
                if (data instanceof io.netty.handler.codec.http.multipart.FileUpload) {
                    long size = ((io.netty.handler.codec.http.multipart.FileUpload) data).length();
                    if (size > maxFileSize) {
                        abort();
                        throw new TooLongFrameException("multipart file '" + data.getName() + "' size " + size
                                + " exceeds limit " + maxFileSize);
                    }
                }
            }
        }
        NettyMultipartWebRequest multipart = new NettyMultipartWebRequest(request, decoder, trailingHeader);
        currentMultipart = false;
        return multipart;
    }

    /**
     * 从 Content-Type 解析 multipart boundary，返回 "--boundary" 字节序列；无法解析返回 null。
     */
    private static byte[] parseBoundary(HttpRequest request) {
        String ct = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (ct == null) {
            return null;
        }
        int bIdx = ct.toLowerCase().indexOf("boundary=");
        if (bIdx < 0) {
            return null;
        }
        String boundary = ct.substring(bIdx + "boundary=".length()).trim();
        // 去引号（boundary 可被双引号包裹）
        if (boundary.length() >= 2 && boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }
        // boundary 若存在后续参数以 ';' 分隔，截断
        int semi = boundary.indexOf(';');
        if (semi >= 0) {
            boundary = boundary.substring(0, semi).trim();
        }
        if (boundary.isEmpty()) {
            return null;
        }
        return ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 增量扫描单个 chunk 的 part header 区字节数（B2）。 跨 chunk 通过 {@link #partHeaderCarry} 保留尾部留样，以正确识别被分片拆断的边界/空行序列。 任一 part 的
     * header 区字节数超过 {@link #maxPartHeaderSize} 即抛 {@link DecoderException}。
     */
    private void scanPartHeaders(HttpContent content) {
        ByteBuf cb = content.content();
        int chunkLen = cb.readableBytes();
        if (chunkLen == 0) {
            return;
        }
        byte[] delim = partBoundaryDelim;
        int carryLen = Math.max(delim.length - 1, 3);
        byte[] combined = new byte[partHeaderCarry.length + chunkLen];
        System.arraycopy(partHeaderCarry, 0, combined, 0, partHeaderCarry.length);
        cb.getBytes(cb.readerIndex(), combined, partHeaderCarry.length, chunkLen);

        int n = combined.length;
        int i = 0;
        while (i < n) {
            if (!inPartHeader) {
                // 尝试在 i 处匹配边界分隔符
                if (i + delim.length <= n) {
                    boolean match = true;
                    for (int j = 0; j < delim.length; j++) {
                        if (combined[i + j] != delim[j]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        // 结束边界 --boundary--：无更多 part，停止扫描
                        if (i + delim.length + 2 <= n && combined[i + delim.length] == '-'
                                && combined[i + delim.length + 1] == '-') {
                            partHeaderScanDone = true;
                            return;
                        }
                        // 合法边界：位于 body 起始，或前有 CRLF（multipart 规范要求边界前有 CRLF）
                        boolean valid = (i == 0);
                        if (!valid && i >= 2) {
                            valid = (combined[i - 1] == '\n' && combined[i - 2] == '\r');
                        }
                        if (valid) {
                            inPartHeader = true;
                            partHeaderLen = 0;
                            i += delim.length;
                            // 跳过边界行尾的 CRLF，开始统计真正的 part header 字节
                            if (i + 2 <= n && combined[i] == '\r' && combined[i + 1] == '\n') {
                                i += 2;
                            }
                            continue;
                        }
                    }
                }
                i++;
            } else {
                // 处于 header 区：统计字节直到遇到终止空行 \r\n\r\n
                if (i + 4 <= n && combined[i] == '\r' && combined[i + 1] == '\n' && combined[i + 2] == '\r'
                        && combined[i + 3] == '\n') {
                    inPartHeader = false;
                    partHeaderLen = 0;
                    i += 4;
                    continue;
                }
                partHeaderLen++;
                if (partHeaderLen > maxPartHeaderSize) {
                    throw new DecoderException(
                            "multipart part header size " + partHeaderLen + " exceeds limit " + maxPartHeaderSize);
                }
                i++;
            }
        }
        // 更新跨 chunk 留样：保留尾部可能跨 chunk 的边界/空行起始
        int start = Math.max(0, n - carryLen);
        partHeaderCarry = Arrays.copyOfRange(combined, start, n);
    }

    public void abort() {
        if (currentMultipart) {
            currentMultipart = false;
            trailingHeader = EmptyHttpHeaders.INSTANCE;
            request = null;
            if (decoder != null) {
                decoder.destroy();
                decoder = null;
            }
        }
    }
}
