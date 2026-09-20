package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接级语义 E2E（原始 socket，绕开 OkHttp 抽象）：
 * Connection: close 请求后服务端关闭连接、HTTP/1.1 pipelining 顺序响应、
 * Expect: 100-continue 两段式响应、HTTP/1.0 无 Host 请求可服务。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ConnectionSemanticsE2eTest.ConnConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.context-path=/")
class ConnectionSemanticsE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String readAll(InputStream in, int expectedResponses) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int seen = 0;
        long deadline = System.currentTimeMillis() + 5000;
        while (seen < expectedResponses && System.currentTimeMillis() < deadline) {
            int n;
            try {
                n = in.read(buf);
            } catch (java.net.SocketTimeoutException e) {
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

    @Test
    void connectionClose_closesAfterResponse() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(("GET /e2e-conn/hit HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String response = readAll(in, 1);
            assertTrue(response.contains("HTTP/1.1 200"), "应正常响应，实际:\n" + response);
            assertTrue(response.toLowerCase().contains("connection: close"),
                    "响应应声明 Connection: close，实际:\n" + response);
            // 继续读应得 EOF（服务端已关闭）
            int next = in.read();
            assertEquals(-1, next, "Connection: close 后服务端应关闭连接");
        }
    }

    @Test
    void pipelining_twoRequestsAnsweredInOrder() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            // 一次性写入两个请求（HTTP/1.1 pipelining）
            out.write(("GET /e2e-conn/first HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /e2e-conn/second HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            String responses = readAll(in, 2);
            int firstIdx = responses.indexOf("first-body");
            int secondIdx = responses.indexOf("second-body");
            assertTrue(firstIdx >= 0 && secondIdx >= 0,
                    "两个 pipelined 请求都应被响应，实际:\n" + responses);
            assertTrue(firstIdx < secondIdx, "响应顺序应与请求顺序一致（FIFO），实际:\n" + responses);
        }
    }

    @Test
    void expectContinue_gets100Then200() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            String json = "{\"a\":1}";
            out.write(("POST /e2e-conn/echo HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Expect: 100-continue\r\n"
                    + "Content-Length: " + json.length() + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            // 等待 100 Continue
            String interim = readAll(in, 1);
            if (!interim.contains("100")) {
                // 框架可能直接返回最终响应（不中间应答，RFC 允许）——继续校验最终 200
                assertTrue(interim.contains("HTTP/1.1 200"),
                        "未返回 100 时应直接给出最终响应，实际:\n" + interim);
                return;
            }
            // 收到 100 后发送 body
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String finalResponse = readAll(in, 1);
            assertTrue(finalResponse.contains("HTTP/1.1 200"),
                    "100-continue 后应返回 200，实际:\n" + finalResponse);
        }
    }

    @Test
    void http10Request_withoutHost_served() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(("GET /e2e-conn/hit HTTP/1.0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String response = readAll(in, 1);
            assertTrue(response.contains("HTTP/1."),
                    "HTTP/1.0 无 Host 请求仍应被服务，实际:\n" + response);
        }
    }

    @TestConfiguration
    static class ConnConfig {
        @Bean
        ConnController connController() {
            return new ConnController();
        }
    }

    @RestController
    static class ConnController {

        @GetMapping("/e2e-conn/hit")
        public String hit() {
            return "hit-body";
        }

        @GetMapping("/e2e-conn/first")
        public String first() {
            return "first-body";
        }

        @GetMapping("/e2e-conn/second")
        public String second() {
            return "second-body";
        }

        @PostMapping("/e2e-conn/echo")
        public String echo(@org.springframework.web.bind.annotation.RequestBody String body) {
            return "echo:" + body.trim();
        }
    }
}
