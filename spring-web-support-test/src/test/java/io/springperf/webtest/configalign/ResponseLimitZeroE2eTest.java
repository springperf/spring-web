package io.springperf.webtest.configalign;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.max-swallow-size=0} E2E：只要错误响应伴随**非空**请求 body 即关闭连接；
 * 零长度 body 的错误响应仍可保活（{@code 0 > 0} 为假）。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ResponseLimitZeroE2eTest.ZeroConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "server.max-swallow-size=0"
        })
class ResponseLimitZeroE2eTest {

    @LocalServerPort
    int port;

    private String readResponses(InputStream in, int expected) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + 5000;
        while (sb.toString().split("HTTP/1\\.1 ", -1).length - 1 < expected
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
        return sb.toString();
    }

    @Test
    void zeroSwallow_errorWithNonEmptyBody_closesConnection() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String body = "z";
            out.write(("POST /e2e-zero/fail HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: text/plain\r\nContent-Length: 1\r\n\r\n" + body)
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();

            String first = readResponses(in, 1);
            assertTrue(first.contains("HTTP/1.1 400"), "应返回 400，实际:\n" + first);
            assertTrue(first.toLowerCase().contains("connection: close"),
                    "max-swallow-size=0 时任何非空 body 的错误响应都应关闭连接，实际:\n" + first);

            int next;
            try {
                next = in.read();
            } catch (SocketTimeoutException e) {
                throw new AssertionError("max-swallow-size=0 时连接应被关闭，但连接仍打开");
            }
            assertEquals(-1, next, "max-swallow-size=0 时连接应被关闭");
        }
    }

    @Test
    void zeroSwallow_errorWithEmptyBody_keepsConnection() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 零长度 body：0 > 0 为假 → 不关闭
            out.write(("GET /e2e-zero/fail-get HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            String first = readResponses(in, 1);
            assertTrue(first.contains("HTTP/1.1 400"), "应返回 400，实际:\n" + first);
            assertTrue(!first.toLowerCase().contains("connection: close"),
                    "零长度 body 的错误响应不应关闭连接，实际:\n" + first);

            out.write(("GET /e2e-zero/ok HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            String second = readResponses(in, 1);
            assertTrue(second.contains("ok-body"),
                    "零长度 body 的错误响应后连接应可复用，实际:\n" + second);
        }
    }

    @TestConfiguration
    static class ZeroConfig {
        @Bean
        ZeroController zeroController() {
            return new ZeroController();
        }
    }

    @RestController
    static class ZeroController {

        @PostMapping("/e2e-zero/fail")
        public ResponseEntity<String> fail(@RequestBody(required = false) String body) {
            return ResponseEntity.status(400).body("bad");
        }

        @GetMapping("/e2e-zero/fail-get")
        public ResponseEntity<String> failGet() {
            return ResponseEntity.status(400).body("bad-get");
        }

        @GetMapping("/e2e-zero/ok")
        public String ok() {
            return "ok-body";
        }
    }
}
