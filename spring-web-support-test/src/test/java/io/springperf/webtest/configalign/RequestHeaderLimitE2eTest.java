package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code server.max-http-request-header-size} E2E：合并请求头超上限被拒绝，
 * 正常小头请求不受影响。
 */
@SpringBootTest(classes = {io.springperf.webtest.SupportTestApplication.class,
                RequestHeaderLimitE2eTest.HeaderConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.max-http-request-header-size=256")
class RequestHeaderLimitE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + "/api" + path;
    }

    @Test
    void normalHeader_ok() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-header/hit"))
                .header("X-Small", "v")
                .build()).execute();
        try {
            assertEquals(200, resp.code(), "小头请求应正常，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void oversizedHeader_rejected() throws Exception {
        // 原始 socket 探针：发送合并头远超 256B 上限的请求（绕过 OkHttp 连接池/重试）
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(3000);
            java.io.OutputStream out = socket.getOutputStream();
            out.write(("GET /api/e2e-header/hit HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "X-Big: " + "x".repeat(512) + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            java.io.InputStream in = socket.getInputStream();
            byte[] buf = new byte[2048];
            int n;
            try {
                n = in.read(buf);
            } catch (java.net.SocketException | java.net.SocketTimeoutException e) {
                n = -1; // 无响应直接关闭：按"已拒绝"处理
            }
            String response = n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8) : "";
            boolean rejected = n <= 0
                    || response.startsWith("HTTP/1.1 4")
                    || response.startsWith("HTTP/1.1 5");
            assertEquals(true, rejected,
                    "超限请求头应被拒绝（4xx/5xx 或连接关闭），实际响应: " + response);
        }
    }

    @TestConfiguration
    static class HeaderConfig {
        @Bean
        HeaderHitController headerHitController() {
            return new HeaderHitController();
        }
    }

    @RestController
    static class HeaderHitController {
        @GetMapping("/e2e-header/hit")
        public String hit() {
            return "ok";
        }
    }
}
