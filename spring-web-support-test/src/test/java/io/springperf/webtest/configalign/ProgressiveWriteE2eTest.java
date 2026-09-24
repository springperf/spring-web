package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Servlet 渐进式写出 E2E（对齐 Tomcat 的 {@code flushBuffer()} / {@code Writer.flush()} / {@code OutputStream.flush()} 语义）：
 * <ul>
 * <li>提交后可继续写入（chunked 续帧），不再是「一次成型」；</li>
 * <li>首段内容必须在处理器结束**之前**到达客户端（渐进性的本质，用到达时序断言）；</li>
 * <li>框架在请求收尾写终止块，客户端能判定响应结束（否则 OkHttp 会读到超时）；</li>
 * <li>多段顺序、UTF-8 多字节跨写入边界、提交后异常/ sendError 均不得破坏已提交的流。</li>
 * </ul>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        ProgressiveWriteE2eTest.ProgressiveConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.servlet.context-path=/")
class ProgressiveWriteE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(15)).build();

    @LocalServerPort
    int port;

    private Socket open() throws IOException {
        Socket s = new Socket("localhost", port);
        s.setSoTimeout(8000);
        return s;
    }

    private static void write(Socket s, String raw) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write(raw.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 读取全部响应，并记录**首个字节**到达的时间戳（用于渐进性时序断言）。 */
    private static String readAllRecordingFirstByte(Socket s, AtomicLong firstByteAt) throws IOException {
        InputStream in = s.getInputStream();
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        while (true) {
            int n;
            try {
                n = in.read(buf);
            } catch (SocketTimeoutException e) {
                break;
            }
            if (n < 0) {
                break;
            }
            if (firstByteAt.get() == 0) {
                firstByteAt.set(System.currentTimeMillis());
            }
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private Response get(String path) throws Exception {
        return CLIENT.newCall(new Request.Builder().url("http://localhost:" + port + path).build()).execute();
    }

    // ==================== 渐进性（时序） ====================

    @Test
    void writerFlush_firstChunkArrivesBeforeHandlerFinishes() throws Exception {
        // 处理器：写第一段 → flush → 睡 1500ms → 写第二段 → flush
        // 首字节必须在睡眠结束前到达，否则「渐进式」名不副实（仍是等整个响应算完）
        long start = System.currentTimeMillis();
        try (Socket s = open()) {
            write(s, "GET /e2e-prog/staged?sleep=1500 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            AtomicLong firstByteAt = new AtomicLong();
            String resp = readAllRecordingFirstByte(s, firstByteAt);
            long firstByteDelay = firstByteAt.get() - start;
            assertTrue(firstByteDelay < 1000, "首段应在处理器结束前到达（实测首字节 " + firstByteDelay + "ms，睡眠 1500ms）");
            assertTrue(resp.contains("stage-1") && resp.contains("stage-2"), "两段都应送达，实际:\n" + resp);
            // 线上 header 名大小写不敏感：Netty 常量以小写写出（transfer-encoding），统一按小写比对
            assertTrue(resp.toLowerCase().contains("transfer-encoding: chunked"), "渐进式输出应使用 chunked 帧，实际:\n" + resp);
        }
    }

    @Test
    void writerFlush_noContentLength_headerAbsent() throws Exception {
        try (Socket s = open()) {
            write(s, "GET /e2e-prog/staged?sleep=200 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String resp = readAllRecordingFirstByte(s, new AtomicLong());
            assertTrue(!resp.contains("Content-Length:"), "已提交为 chunked 后不得再带 Content-Length（两者互斥），实际:\n" + resp);
        }
    }

    // ==================== 三种 flush 入口 ====================

    @Test
    void outputStreamFlush_streamsProgressively() throws Exception {
        try (Socket s = open()) {
            write(s, "GET /e2e-prog/out?sleep=600 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            AtomicLong firstByteAt = new AtomicLong();
            long start = System.currentTimeMillis();
            String resp = readAllRecordingFirstByte(s, firstByteAt);
            assertEquals(1, countStatusLines(resp), "只应有一个响应，实际:\n" + resp);
            assertTrue(resp.contains("out-1") && resp.contains("out-2"),
                    "getOutputStream().flush() 后仍应能继续写，实际:\n" + resp);
            assertTrue(firstByteAt.get() - start < 500, "首段应先于处理器结束到达，实测 " + (firstByteAt.get() - start) + "ms");
        }
    }

    @Test
    void flushBuffer_multipleChunks_orderPreserved() throws Exception {
        try (Socket s = open()) {
            write(s, "GET /e2e-prog/multi HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String resp = readAllRecordingFirstByte(s, new AtomicLong());
            int c1 = resp.indexOf("chunk-1");
            int c2 = resp.indexOf("chunk-2");
            int c3 = resp.indexOf("chunk-3");
            int c4 = resp.indexOf("chunk-4");
            assertTrue(c1 >= 0 && c2 > c1 && c3 > c2 && c4 > c3, "分段内容顺序必须保持，实际:\n" + resp);
        }
    }

    // ==================== 客户端视角完整性 ====================

    @Test
    void progressiveResponse_clientReadsCompleteBody() throws Exception {
        // 走 OkHttp（会解析 chunked）：能完整读到 body 即证明终止块被正确写出（否则读超时）
        Response resp = get("/e2e-prog/staged?sleep=200");
        try {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("stage-1\nstage-2\n", body, "客户端应读到完整且有序的内容");
        } finally {
            resp.close();
        }
    }

    @Test
    void progressiveThenReturn_streamTerminatedByFramework() throws Exception {
        // 处理器只写一段 + flush 后就返回：收尾必须由框架写终止块
        Response resp = get("/e2e-prog/flush-then-return");
        try {
            assertEquals(200, resp.code());
            assertEquals("only-part\n", resp.body().string(), "未显式收尾时框架应补终止块，客户端读到完整内容");
        } finally {
            resp.close();
        }
    }

    @Test
    void writerWriteWithoutFlush_contentStillDelivered() throws Exception {
        // Writer 为 Tomcat 语义（autoFlush=false）：未 flush 的内容必须在提交前被刷出，不得丢失
        Response resp = get("/e2e-prog/write-no-flush");
        try {
            assertEquals(200, resp.code());
            assertEquals("no-flush-body", resp.body().string(), "未 flush 的 Writer 内容应在提交前刷入响应体");
        } finally {
            resp.close();
        }
    }

    // ==================== 编码与异常 ====================

    @Test
    void progressiveUtf8_multibyteAcrossChunks() throws Exception {
        Response resp = get("/e2e-prog/utf8");
        try {
            assertEquals(200, resp.code());
            assertEquals("你好世界", resp.body().string(), "跨 chunk 的 UTF-8 多字节字符应完整");
        } finally {
            resp.close();
        }
    }

    @Test
    void sendErrorAfterStreaming_doesNotCorruptStream() throws Exception {
        // 已提交为 chunked 后 sendError 无法改写状态码（与 Tomcat 一致）：不得追加错误体或第二个响应
        try (Socket s = open()) {
            write(s, "GET /e2e-prog/flush-then-sendError HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String resp = readAllRecordingFirstByte(s, new AtomicLong());
            assertEquals(1, countStatusLines(resp), "只应有一个响应，实际:\n" + resp);
            assertTrue(resp.contains("200"), "已提交的状态码不得被改写，实际:\n" + resp);
            assertTrue(resp.contains("streamed-part"), "已提交内容应送达，实际:\n" + resp);
            assertTrue(!resp.contains("500"), "不得把错误响应追加进已提交的流，实际:\n" + resp);
        }
    }

    @Test
    void exceptionAfterStreaming_streamEndedWithoutHang() throws Exception {
        try (Socket s = open()) {
            write(s, "GET /e2e-prog/flush-then-throw HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String resp = readAllRecordingFirstByte(s, new AtomicLong());
            assertEquals(1, countStatusLines(resp), "只应有一个响应，实际:\n" + resp);
            assertTrue(resp.contains("before-throw"), "已提交内容应送达，实际:\n" + resp);
        }
    }

    @Test
    void headRequest_flushBuffer_noBody() throws Exception {
        Response resp = CLIENT.newCall(
                new Request.Builder().url("http://localhost:" + port + "/e2e-prog/staged?sleep=100").head().build())
                .execute();
        try {
            assertEquals(200, resp.code(), "HEAD 应正常响应，实际 " + resp.code());
            assertEquals("", resp.body().string(), "HEAD 不得返回 body");
        } finally {
            resp.close();
        }
    }

    private static int countStatusLines(String raw) {
        int count = 0;
        int idx = raw.indexOf("HTTP/1.1 ");
        while (idx >= 0) {
            count++;
            idx = raw.indexOf("HTTP/1.1 ", idx + 1);
        }
        return count;
    }

    @TestConfiguration
    static class ProgressiveConfig {
        @Bean
        ProgressiveController progressiveController() {
            return new ProgressiveController();
        }
    }

    @RestController
    static class ProgressiveController {

        /** 写一段 → flush → 睡眠 → 再写一段 → flush（渐进性主用例）。 */
        @GetMapping("/e2e-prog/staged")
        public void staged(@RequestParam long sleep, HttpServletResponse response) throws Exception {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("stage-1\n");
            response.getWriter().flush();
            Thread.sleep(sleep);
            response.getWriter().write("stage-2\n");
            response.getWriter().flush();
        }

        /** getOutputStream().flush() 入口。 */
        @GetMapping("/e2e-prog/out")
        public void out(@RequestParam long sleep, HttpServletResponse response) throws Exception {
            response.setContentType("text/plain;charset=UTF-8");
            response.getOutputStream().write("out-1\n".getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
            Thread.sleep(sleep);
            response.getOutputStream().write("out-2\n".getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
        }

        /** 多段顺序。 */
        @GetMapping("/e2e-prog/multi")
        public void multi(HttpServletResponse response) throws Exception {
            response.setContentType("text/plain;charset=UTF-8");
            for (int i = 1; i <= 4; i++) {
                response.getWriter().write("chunk-" + i + "\n");
                response.flushBuffer();
            }
        }

        /** 写一段 + flush 后直接返回（收尾由框架负责）。 */
        @GetMapping("/e2e-prog/flush-then-return")
        public void flushThenReturn(HttpServletResponse response) throws IOException {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("only-part\n");
            response.getWriter().flush();
        }

        /** 完全不 flush：内容仍应在提交前刷出。 */
        @GetMapping("/e2e-prog/write-no-flush")
        public void writeNoFlush(HttpServletResponse response) throws IOException {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("no-flush-body");
        }

        /** UTF-8 跨 chunk。 */
        @GetMapping("/e2e-prog/utf8")
        public void utf8(HttpServletResponse response) throws Exception {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("你好");
            response.getWriter().flush();
            Thread.sleep(80);
            response.getWriter().write("世界");
            response.getWriter().flush();
        }

        /** 已提交后 sendError。 */
        @GetMapping("/e2e-prog/flush-then-sendError")
        public void flushThenSendError(HttpServletResponse response) throws Exception {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("streamed-part\n");
            response.getWriter().flush();
            try {
                response.sendError(500, "too-late");
            } catch (IllegalStateException ignore) {
                // Tomcat 语义：已提交后 sendError 抛 IllegalStateException 亦为合规行为
            }
        }

        /** 已提交后抛异常。 */
        @GetMapping("/e2e-prog/flush-then-throw")
        public void flushThenThrow(HttpServletResponse response) throws Exception {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("before-throw\n");
            response.getWriter().flush();
            throw new IllegalStateException("boom-after-commit");
        }
    }
}
