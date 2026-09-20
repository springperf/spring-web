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
 * {@code server.max-swallow-size=1024} E2E（原始 socket，必须观察真实连接复用/关闭）：
 *
 * <ul>
 *   <li>错误响应（4xx）+ 请求 body 未超上限 → 保持 keep-alive，同连接后续请求可被服务；</li>
 *   <li>错误响应 + 请求 body 超上限 → 降级为 {@code Connection: close} 并真实关闭连接；</li>
 *   <li>成功响应（2xx）+ 大 body → 不受 swallow 限制影响，连接照常复用（限制只作用于错误响应）。</li>
 * </ul>
 *
 * <p>swallow 的取舍：聚合模型下 body 已被读完，若在错误响应后继续复用仍带超大 body 的连接，
 * 只会让坏连接占用资源——超过上限即放弃复用。</p>
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ResponseLimitSwallowE2eTest.SwallowConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "server.max-swallow-size=1024"
        })
class ResponseLimitSwallowE2eTest {

    /** 小于上限（1024）的请求 body。 */
    private static final int SMALL_BODY = 100;
    /** 大于上限（1024）的请求 body。 */
    private static final int LARGE_BODY = 4096;

    @LocalServerPort
    int port;

    /** 读出 {@code expectedResponses} 个响应（以 "HTTP/1.1 " 出现次数计），或被超时/EOF 打断。 */
    private String readResponses(InputStream in, int expectedResponses) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int seen = 0;
        long deadline = System.currentTimeMillis() + 5000;
        while (seen < expectedResponses && System.currentTimeMillis() < deadline) {
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
            seen = sb.toString().split("HTTP/1\\.1 ", -1).length - 1;
        }
        return sb.toString();
    }

    private static String post(String path, int bodyBytes) {
        String body = "x".repeat(bodyBytes);
        return "POST " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + bodyBytes + "\r\n\r\n"
                + body;
    }

    private static String get(String path) {
        return "GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
    }

    @Test
    void errorResponse_smallBody_keepsConnectionReusable() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(post("/e2e-limit/fail", SMALL_BODY).getBytes(StandardCharsets.UTF_8));
            out.flush();
            String first = readResponses(in, 1);
            assertTrue(first.contains("HTTP/1.1 400"), "应返回 400，实际:\n" + first);

            // 关键：同连接第二个请求必须被服务（swallow 未超限 → 保活）
            out.write(get("/e2e-limit/ok").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String second = readResponses(in, 1);
            assertTrue(second.contains("HTTP/1.1 200") && second.contains("ok-body"),
                    "body 未超 swallow 上限时连接应可复用，实际:\n" + second);
        }
    }

    @Test
    void errorResponse_largeBody_closesConnection() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(post("/e2e-limit/fail", LARGE_BODY).getBytes(StandardCharsets.UTF_8));
            out.flush();
            String first = readResponses(in, 1);
            assertTrue(first.contains("HTTP/1.1 400"), "应返回 400，实际:\n" + first);
            assertTrue(first.toLowerCase().contains("connection: close"),
                    "body 超过 swallow 上限时响应应声明 Connection: close，实际:\n" + first);

            // 连接必须真实关闭：继续读应得 EOF
            int next;
            try {
                next = in.read();
            } catch (SocketTimeoutException e) {
                throw new AssertionError("超过 swallow 上限后服务端应关闭连接，但连接仍打开");
            }
            assertEquals(-1, next, "超过 swallow 上限后服务端应关闭连接");
        }
    }

    @Test
    void successResponse_largeBody_keepsConnectionReusable() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 大 body 但成功响应：swallow 限制只作用于 4xx/5xx
            out.write(post("/e2e-limit/echo", LARGE_BODY).getBytes(StandardCharsets.UTF_8));
            out.flush();
            String first = readResponses(in, 1);
            assertTrue(first.contains("HTTP/1.1 200"), "应返回 200，实际:\n" + first);
            assertTrue(!first.toLowerCase().contains("connection: close"),
                    "成功响应不应因大 body 被关闭连接，实际:\n" + first);

            out.write(get("/e2e-limit/ok").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String second = readResponses(in, 1);
            assertTrue(second.contains("HTTP/1.1 200") && second.contains("ok-body"),
                    "成功响应后连接应可复用，实际:\n" + second);
        }
    }

    @Test
    void errorResponse_boundaryBodyExactlyAtLimit_keepsConnection() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 边界值：恰好等于上限（1024），判定为「未超过」→ 保活
            out.write(post("/e2e-limit/fail", 1024).getBytes(StandardCharsets.UTF_8));
            out.flush();
            String first = readResponses(in, 1);
            assertTrue(first.contains("HTTP/1.1 400"), "应返回 400，实际:\n" + first);
            assertTrue(!first.toLowerCase().contains("connection: close"),
                    "body 恰好等于上限时不应关闭连接（> 才关闭），实际:\n" + first);
        }
    }

    @TestConfiguration
    static class SwallowConfig {
        @Bean
        SwallowController swallowController() {
            return new SwallowController();
        }
    }

    @RestController
    static class SwallowController {

        @PostMapping("/e2e-limit/fail")
        public ResponseEntity<String> fail(@RequestBody(required = false) String body) {
            return ResponseEntity.status(400).body("bad-request-body");
        }

        @PostMapping("/e2e-limit/echo")
        public String echo(@RequestBody(required = false) String body) {
            return "echo:" + (body == null ? 0 : body.length());
        }

        @GetMapping("/e2e-limit/ok")
        public String ok() {
            return "ok-body";
        }
    }
}
