package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 负值 = 不限的 E2E 口径：
 *
 * <ul>
 *   <li>{@code server.max-swallow-size=-1}：错误响应 + 超大 body 也不关闭连接（永不因 swallow 降级）；</li>
 *   <li>{@code server.max-http-response-header-size=-1}：响应头总量不设上限，超大响应头原样写出。</li>
 * </ul>
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ResponseLimitUnlimitedE2eTest.UnlimitedConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "server.max-swallow-size=-1",
                "server.max-http-response-header-size=-1"
        })
class ResponseLimitUnlimitedE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    /** 可控的“超大”响应头值（远超默认 8KB 上限）。 */
    private static final int BIG_HEADER_LEN = 20000;

    @Test
    void negativeSwallowSize_errorWithHugeBody_keepsConnectionAlive() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String body = "y".repeat(65536);
            out.write(("POST /e2e-unlimited/fail HTTP/1.1\r\n"
                    + "Host: localhost\r\nContent-Type: text/plain\r\n"
                    + "Content-Length: " + body.length() + "\r\n\r\n" + body)
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();

            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            long deadline = System.currentTimeMillis() + 5000;
            while (sb.toString().split("HTTP/1\\.1 ", -1).length - 1 < 1
                    && System.currentTimeMillis() < deadline) {
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
            String first = sb.toString();
            assertTrue(first.contains("HTTP/1.1 400"), "应返回 400，实际:\n" + first);
            assertTrue(!first.toLowerCase().contains("connection: close"),
                    "max-swallow-size=-1 时不应因 body 大而关闭连接，实际:\n" + first);

            // 连接仍可复用
            out.write("GET /e2e-unlimited/ok HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            StringBuilder sb2 = new StringBuilder();
            long deadline2 = System.currentTimeMillis() + 5000;
            while (sb2.toString().split("HTTP/1\\.1 ", -1).length - 1 < 1
                    && System.currentTimeMillis() < deadline2) {
                int n;
                try {
                    n = in.read(buf);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (n < 0) {
                    break;
                }
                sb2.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            assertTrue(sb2.toString().contains("ok-body"),
                    "负值上限下错误响应后连接应仍可复用，实际:\n" + sb2);
        }
    }

    @Test
    void negativeResponseHeaderSize_hugeHeadersPassedThrough() throws Exception {
        Response resp = CLIENT.newCall(new Request.Builder()
                .url("http://localhost:" + port + "/e2e-unlimited/big-header")
                .get().build()).execute();
        try {
            assertEquals(200, resp.code(),
                    "max-http-response-header-size=-1 时不限制响应头，原响应应保持 200");
            String big = resp.header("X-Big");
            assertTrue(big != null && big.length() == BIG_HEADER_LEN,
                    "超大响应头应原样透出，实际长度=" + (big == null ? "null" : big.length()));
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class UnlimitedConfig {
        @Bean
        UnlimitedController unlimitedController() {
            return new UnlimitedController();
        }
    }

    @RestController
    static class UnlimitedController {

        @PostMapping("/e2e-unlimited/fail")
        public ResponseEntity<String> fail(@RequestBody(required = false) String body) {
            return ResponseEntity.status(400).body("bad");
        }

        @GetMapping("/e2e-unlimited/ok")
        public String ok() {
            return "ok-body";
        }

        @GetMapping("/e2e-unlimited/big-header")
        public ResponseEntity<String> bigHeader() {
            return ResponseEntity.ok()
                    .header("X-Big", "b".repeat(BIG_HEADER_LEN))
                    .body("done");
        }
    }
}
