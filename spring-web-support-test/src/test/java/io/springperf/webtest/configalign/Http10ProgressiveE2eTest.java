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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP/1.0 渐进式输出 E2E：chunked 分帧在 HTTP/1.0 中不存在，1.0 客户端会把分块标记 当 body 收下。对齐 Tomcat：退化为 close-delimited——响应头不带
 * Content-Length / Transfer-Encoding，连接关闭即 body 结束。
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        Http10ProgressiveE2eTest.Cfg.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.servlet.context-path=/")
class Http10ProgressiveE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private Socket open() throws IOException {
        Socket s = new Socket("localhost", port);
        s.setSoTimeout(6000);
        return s;
    }

    private static String readAll(Socket s) throws IOException {
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
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    @Test
    void http10_progressive_closeDelimited_noChunkedFraming() throws Exception {
        try (Socket s = open()) {
            OutputStream out = s.getOutputStream();
            out.write("GET /e2e-h10/staged HTTP/1.0\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            AtomicLong firstByteAt = new AtomicLong();
            long start = System.currentTimeMillis();
            String resp = readAll(s);
            assertTrue(resp.startsWith("HTTP/1.1 200"), "实际:\n" + resp);
            String lower = resp.toLowerCase();
            assertFalse(lower.contains("transfer-encoding: chunked"), "HTTP/1.0 不得发 chunked 帧，实际:\n" + resp);
            assertFalse(lower.contains("content-length:"), "close-delimited 不得带 Content-Length（长度未知），实际:\n" + resp);
            assertTrue(lower.contains("connection: close"), "close-delimited 必须显式 Connection: close，实际:\n" + resp);
            assertTrue(resp.contains("h10-stage-1") && resp.contains("h10-stage-2"), "两段都应送达，实际:\n" + resp);
            assertTrue(resp.indexOf("h10-stage-1") < resp.indexOf("h10-stage-2"), "顺序保持");
        }
    }

    @Test
    void http10_nonProgressive_stillHasContentLength() throws Exception {
        // 未用 flushBuffer 的普通响应不受影响：HTTP/1.0 一次性响应仍带 Content-Length
        try (Socket s = open()) {
            OutputStream out = s.getOutputStream();
            out.write("GET /e2e-h10/plain HTTP/1.0\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            String resp = readAll(s);
            assertTrue(resp.startsWith("HTTP/1.1 200"), "实际:\n" + resp);
            assertTrue(resp.toLowerCase().contains("content-length: 5"), "一次性响应仍应带真实 Content-Length，实际:\n" + resp);
            assertTrue(resp.endsWith("h10-p"), "body 完整，实际:\n" + resp);
        }
    }

    @Test
    void http11_sameEndpoint_stillChunked() throws Exception {
        // 同一端点，HTTP/1.1 客户端仍走 chunked（版本感知只影响 1.0）
        Response resp = CLIENT
                .newCall(new Request.Builder().url("http://localhost:" + port + "/e2e-h10/staged").build()).execute();
        try {
            assertEquals(200, resp.code());
            assertEquals("h10-stage-1\nh10-stage-2\n", resp.body().string());
            assertEquals("chunked", resp.header("Transfer-Encoding"), "HTTP/1.1 渐进式输出应仍是 chunked");
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class Cfg {
        @Bean
        H10Controller h10Controller() {
            return new H10Controller();
        }
    }

    @RestController
    static class H10Controller {
        @GetMapping("/e2e-h10/staged")
        public void staged(HttpServletResponse response) throws Exception {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("h10-stage-1\n");
            response.getWriter().flush();
            Thread.sleep(150);
            response.getWriter().write("h10-stage-2\n");
            response.getWriter().flush();
        }

        @GetMapping("/e2e-h10/plain")
        public void plain(HttpServletResponse response) throws IOException {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("h10-p");
        }
    }
}
