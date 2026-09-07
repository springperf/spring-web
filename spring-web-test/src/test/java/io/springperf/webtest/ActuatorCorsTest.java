package io.springperf.webtest;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j

/**
 * Actuator CORS 配置集成测试。
 * <p>验证 {@code management.endpoints.web.cors.*} 配置属性对 Actuator 端点生效。
 * 使用独立的 Spring 上下文以避免影响其他测试。</p>
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "server.port=9092",
        "server.servlet.context-path=/api",
        "management.endpoints.web.exposure.include=*",
        "management.endpoints.web.cors.allowed-origins=http://example.com",
        "management.endpoints.web.cors.allowed-methods=GET,POST"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ActuatorCorsTest {

    public static final okhttp3.OkHttpClient CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .readTimeout(java.time.Duration.ofSeconds(10))
            .writeTimeout(java.time.Duration.ofSeconds(10))
            .build();

    private final String actuatorBase = "http://localhost:9092/api/actuator";

    @Test
    void corsPreflight_shouldReturnAllowOrigin() throws Exception {
        Request req = new Request.Builder()
                .url(actuatorBase + "/health")
                .header("Origin", "http://example.com")
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", null)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            int code = resp.code();
            assertTrue(code == 200 || code == 204,
                    "Expected 200 or 204 for CORS preflight, got " + code);
            String allowOrigin = resp.header("Access-Control-Allow-Origin");
            assertEquals("http://example.com", allowOrigin);
        }
    }

    @Test
    void corsGet_shouldIncludeCorsHeaders() throws Exception {
        Request req = new Request.Builder()
                .url(actuatorBase + "/health")
                .header("Origin", "http://example.com")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String allowOrigin = resp.header("Access-Control-Allow-Origin");
            assertEquals("http://example.com", allowOrigin);
            String body = Objects.toString(resp.body().string(), "");
            assertTrue(body.contains("\"status\""));
        }
    }

    @Test
    void corsGet_withDisallowedOrigin_shouldBeRejected() throws Exception {
        Request req = new Request.Builder()
                .url(actuatorBase + "/health")
                .header("Origin", "http://evil.com")
                .get()
                .build();
        // 配置存在且允许来源仅为 example.com：evil.com 的实际请求应被 CORS 处理器拒绝（403），
        // 且响应头不得回显被拒来源。不得以"超时豁免"掩盖实现缺陷。
        try (Response resp = CLIENT.newCall(req).execute()) {
            String allowOrigin = resp.header("Access-Control-Allow-Origin");
            assertFalse("http://evil.com".equals(allowOrigin),
                    "Disallowed origin should not be in CORS header");
            assertTrue(resp.code() == 403,
                    "不被允许的来源应被 403 拒绝，实际 " + resp.code());
        }
    }
}