package io.springperf.web.http.support;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SupportMultipartResolver#finish()} 的 {@code server.http.multipart.max-part-count} 校验：
 * part 总数（file + attribute）超过阈值抛 {@link DecoderException}，由 SupportMultipartAggregator 转 400。
 */
class SupportMultipartResolverTest {

    private static final String BOUNDARY = "BOUNDARY";

    // ---- 用例资源登记：multipart 的 part / decoder / undecodedChunk 由返回的 NettyMultipartWebRequest
    // 持有，只有 release() 到 refCnt=0 才会 decoder.destroy()。用例不释放即泄漏（paranoid 全量实测
    // 本类 22 条报告、生产路径 0 条）。故凡 finish() 产出的请求与创建过的 resolver 一律登记，
    // 由 @AfterEach 统一收口 —— 避免每个新增用例都要记得手工释放。
    private static final List<NettyMultipartWebRequest> CREATED_REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<SupportMultipartResolver> CREATED_RESOLVERS = new CopyOnWriteArrayList<>();

    @AfterEach
    void releaseMultipartResources() {
        for (NettyMultipartWebRequest req : CREATED_REQUESTS) {
            // refCnt 归零时内部按序 destroy() decoder（释 undecodedChunk 与磁盘临时文件）再释放 parts
            req.release();
        }
        CREATED_REQUESTS.clear();
        for (SupportMultipartResolver resolver : CREATED_RESOLVERS) {
            // 未走到 finish 的用例（仅 start+consume）：销毁 decoder；已 finish 时 abort() 为 no-op
            resolver.abort();
        }
        CREATED_RESOLVERS.clear();
    }

    /** finish() 包装：登记返回的请求，交由 {@link #releaseMultipartResources()} 释放。 */
    private static NettyMultipartWebRequest finish(SupportMultipartResolver resolver) {
        NettyMultipartWebRequest req = resolver.finish();
        CREATED_REQUESTS.add(req);
        return req;
    }

    private static SupportMultipartResolver track(SupportMultipartResolver resolver) {
        CREATED_RESOLVERS.add(resolver);
        return resolver;
    }

    private static byte[] multipartBody(int partCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < partCount; i++) {
            sb.append("--").append(BOUNDARY).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"f").append(i).append("\"\r\n\r\n");
            sb.append("v").append(i).append("\r\n");
        }
        sb.append("--").append(BOUNDARY).append("--\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static HttpRequest newRequest(byte[] body) {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", Unpooled.wrappedBuffer(new byte[0]));
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "multipart/form-data; boundary=" + BOUNDARY);
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        return req;
    }

    private static SupportMultipartResolver startAndConsume(byte[] body, int maxPartCount) {
        SupportMultipartResolver resolver = track(new SupportMultipartResolver(-1, maxPartCount));
        resolver.start(newRequest(body));
        resolver.consume(new DefaultLastHttpContent(Unpooled.wrappedBuffer(body)));
        return resolver;
    }

    @Test
    void finish_throwsWhenPartCountExceedsLimit() {
        byte[] body = multipartBody(3);
        SupportMultipartResolver resolver = startAndConsume(body, 2);
        DecoderException ex = assertThrows(DecoderException.class, resolver::finish);
        assertTrue(ex.getMessage().contains("part count"), ex.getMessage());
    }

    @Test
    void finish_okWhenWithinLimit() {
        byte[] body = multipartBody(3);
        SupportMultipartResolver resolver = startAndConsume(body, 10);
        NettyMultipartWebRequest req = finish(resolver);
        assertEquals(3, req.getInterfaceHttpDataList().size());
    }

    @Test
    void finish_unlimitedWhenNonPositive() {
        byte[] body = multipartBody(50);
        SupportMultipartResolver resolver = startAndConsume(body, 0);
        NettyMultipartWebRequest req = finish(resolver);
        assertEquals(50, req.getInterfaceHttpDataList().size());
    }

    // ========== server.http.multipart.max-part-header-size（B2 增量扫描） ==========

    /** 构造一个 part header 区被 X-Pad 撑大到 headerPadBytes 的 multipart body。 */
    private static byte[] multipartBodyWithLargeHeader(int headerPadBytes) {
        char[] pad = new char[headerPadBytes];
        Arrays.fill(pad, 'a');
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(BOUNDARY).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"f0\"\r\n");
        sb.append("X-Pad: ").append(pad).append("\r\n");
        sb.append("\r\n");
        sb.append("v0\r\n");
        sb.append("--").append(BOUNDARY).append("--\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static SupportMultipartResolver startAndConsume(
            byte[] body, int maxPartCount, int maxPartHeaderSize) {
        SupportMultipartResolver resolver = new SupportMultipartResolver(-1, maxPartCount, maxPartHeaderSize);
        resolver.start(newRequest(body));
        resolver.consume(new DefaultLastHttpContent(Unpooled.wrappedBuffer(body)));
        return resolver;
    }

    /** 分两 chunk 消费：验证被分片拆断的 part header 仍能被跨 chunk 扫描识别。 */
    private static SupportMultipartResolver startAndConsumeInChunks(
            byte[] body, int maxPartHeaderSize, int splitAt) {
        SupportMultipartResolver resolver = track(new SupportMultipartResolver(-1, -1, maxPartHeaderSize));
        resolver.start(newRequest(body));
        byte[] first = Arrays.copyOfRange(body, 0, splitAt);
        byte[] second = Arrays.copyOfRange(body, splitAt, body.length);
        resolver.consume(new DefaultHttpContent(Unpooled.wrappedBuffer(first)));
        resolver.consume(new DefaultLastHttpContent(Unpooled.wrappedBuffer(second)));
        return resolver;
    }

    @Test
    void consume_throwsWhenPartHeaderExceedsLimit() {
        // 上限 64 字节，X-Pad 撑到 200 字节 => 该 part header 区远超上限，consume 阶段即抛
        byte[] body = multipartBodyWithLargeHeader(200);
        DecoderException ex = assertThrows(DecoderException.class, () -> startAndConsume(body, -1, 64));
        assertTrue(ex.getMessage().contains("part header size"), ex.getMessage());
    }

    @Test
    void consume_okWhenPartHeaderWithinLimit() {
        byte[] body = multipartBodyWithLargeHeader(10);
        SupportMultipartResolver resolver = startAndConsume(body, -1, 64);
        NettyMultipartWebRequest req = finish(resolver);
        assertEquals(1, req.getInterfaceHttpDataList().size());
    }

    @Test
    void consume_detectsOversizedHeaderAcrossChunks() {
        // 在 X-Pad 中间把 body 切成两 chunk，验证跨 chunk 仍能识别超限
        byte[] body = multipartBodyWithLargeHeader(200);
        int splitAt = body.length / 2;
        DecoderException ex = assertThrows(DecoderException.class,
                () -> startAndConsumeInChunks(body, 64, splitAt));
        assertTrue(ex.getMessage().contains("part header size"), ex.getMessage());
    }

    @Test
    void consume_unlimitedWhenNonPositive() {
        byte[] body = multipartBodyWithLargeHeader(500);
        SupportMultipartResolver resolver = startAndConsume(body, -1, 0);
        NettyMultipartWebRequest req = finish(resolver);
        assertEquals(1, req.getInterfaceHttpDataList().size());
    }

    // ========== spring.servlet.multipart.max-file-size ==========

    /** 构造一个含单个上传文件（file part）的 multipart body，文件内容为 fileBytes 个 'x'。 */
    private static byte[] multipartFileBody(int fileBytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(BOUNDARY).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n");
        sb.append("Content-Type: text/plain\r\n\r\n");
        for (int i = 0; i < fileBytes; i++) {
            sb.append('x');
        }
        sb.append("\r\n");
        sb.append("--").append(BOUNDARY).append("--\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static SupportMultipartResolver startAndConsumeWithFileLimit(byte[] body, long maxFileSize) {
        SupportMultipartResolver resolver = track(new SupportMultipartResolver(-1, -1, 8192, maxFileSize));
        resolver.start(newRequest(body));
        resolver.consume(new DefaultLastHttpContent(Unpooled.wrappedBuffer(body)));
        return resolver;
    }

    @Test
    void finish_throwsWhenFileSizeExceedsLimit() {
        // 限制 10 字节，文件 100 字节 → finish 阶段抛 TooLongFrameException（→413）
        byte[] body = multipartFileBody(100);
        SupportMultipartResolver resolver = startAndConsumeWithFileLimit(body, 10);
        io.netty.handler.codec.TooLongFrameException ex = assertThrows(
                io.netty.handler.codec.TooLongFrameException.class, resolver::finish);
        assertTrue(ex.getMessage().contains("exceeds limit"), ex.getMessage());
    }

    @Test
    void finish_okWhenFileSizeWithinLimit() {
        byte[] body = multipartFileBody(100);
        SupportMultipartResolver resolver = startAndConsumeWithFileLimit(body, 1000);
        NettyMultipartWebRequest req = finish(resolver);
        assertEquals(1, req.getInterfaceHttpDataList().size());
    }

    @Test
    void finish_unlimitedFileSizeWhenNonPositive() {
        byte[] body = multipartFileBody(10_000);
        SupportMultipartResolver resolver = startAndConsumeWithFileLimit(body, -1);
        NettyMultipartWebRequest req = finish(resolver);
        assertEquals(1, req.getInterfaceHttpDataList().size());
    }

    // ========== spring.servlet.multipart.file-size-threshold / location ==========

    /** 用指定阈值/location 构造 resolver 并跑完一个 multipart 请求。 */
    private static NettyMultipartWebRequest runWithFactoryConfig(
            byte[] body, long fileSizeThreshold, String location) {
        SupportMultipartResolver resolver = track(new SupportMultipartResolver(
                -1, -1, 8192, -1L, fileSizeThreshold, location));
        resolver.start(newRequest(body));
        resolver.consume(new DefaultLastHttpContent(Unpooled.wrappedBuffer(body)));
        return finish(resolver);
    }

    /** 判定 part 是否已落盘：Netty 的 MixedFileUpload 在超过阈值时 getFile() 才非 null。 */
    private static boolean isOnDisk(io.netty.handler.codec.http.multipart.FileUpload upload) {
        try {
            return upload.getFile() != null;
        } catch (java.io.IOException e) {
            // getFile() 异常视为未落盘（尚未写盘）
            return false;
        }
    }

    @Test
    void fileSizeThreshold_smallThreshold_forcesDiskStorage() {
        // 阈值 1 字节 + 文件 100 字节 → 超阈值，应落盘（getFile() 非 null）
        byte[] body = multipartFileBody(100);
        NettyMultipartWebRequest req = runWithFactoryConfig(body, 1L, "");
        io.netty.handler.codec.http.multipart.FileUpload upload =
                (io.netty.handler.codec.http.multipart.FileUpload) req.getInterfaceHttpDataList().get(0);
        assertTrue(isOnDisk(upload), "超过阈值应落盘（getFile() 非 null）");
    }

    @Test
    void fileSizeThreshold_largeThreshold_keepsInMemory() {
        // 阈值 1MB + 文件 100 字节 → 低于阈值，应留内存（getFile() 为 null）
        byte[] body = multipartFileBody(100);
        NettyMultipartWebRequest req = runWithFactoryConfig(body, 1024L * 1024, "");
        io.netty.handler.codec.http.multipart.FileUpload upload =
                (io.netty.handler.codec.http.multipart.FileUpload) req.getInterfaceHttpDataList().get(0);
        assertFalse(isOnDisk(upload), "低于阈值应留内存（getFile() 为 null）");
    }

    @Test
    void fileSizeThreshold_unset_usesFrameworkDefault16KB() {
        // 未配置（UNSET）：沿用 Netty MINSIZE=16KB → 100 字节留内存
        byte[] body = multipartFileBody(100);
        NettyMultipartWebRequest req = runWithFactoryConfig(
                body, io.springperf.web.server.MultipartConfig.FILE_SIZE_THRESHOLD_UNSET, "");
        io.netty.handler.codec.http.multipart.FileUpload upload =
                (io.netty.handler.codec.http.multipart.FileUpload) req.getInterfaceHttpDataList().get(0);
        assertFalse(isOnDisk(upload), "未配置时沿用 16KB 默认，小文件应留内存");
    }

    @Test
    void location_customDir_diskFilePlacedThere(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws Exception {
        byte[] body = multipartFileBody(100);
        NettyMultipartWebRequest req = runWithFactoryConfig(body, 1L, tempDir.toString());
        io.netty.handler.codec.http.multipart.FileUpload upload =
                (io.netty.handler.codec.http.multipart.FileUpload) req.getInterfaceHttpDataList().get(0);
        assertTrue(isOnDisk(upload), "应落盘");
        String path = upload.getFile().getAbsolutePath();
        assertTrue(path.startsWith(tempDir.toString()),
                "落盘文件应位于配置的 location 下，实际：" + path);
    }

    @Test
    void location_blank_usesNettyDefault() {
        // 空 location：不调用 setBaseDir，不应抛异常
        byte[] body = multipartFileBody(10);
        assertDoesNotThrow(() -> runWithFactoryConfig(body, -1L, "  "));
    }

    @Test
    void fullConstructor_createsFactoryWithoutError() {
        SupportMultipartResolver resolver = new SupportMultipartResolver(-1, -1, 8192, -1L, 0L, "");
        assertNotNull(resolver);
    }
}
