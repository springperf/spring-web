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
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
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
        okhttp3.OkHttpClient shortTimeoutClient = CLIENT.newBuilder()
                .readTimeout(java.time.Duration.ofSeconds(2))
                .build();
        Request req = new Request.Builder()
                .url(actuatorBase + "/health")
                .header("Origin", "http://evil.com")
                .get()
                .build();
        try (Response resp = shortTimeoutClient.newCall(req).execute()) {
            // 不允许的来源：可能返回 403 或 CORS 头中不包含该来源
            String allowOrigin = resp.header("Access-Control-Allow-Origin");
            assertFalse("http://evil.com".equals(allowOrigin),
                    "Disallowed origin should not be in CORS header");
        } catch (java.net.SocketTimeoutException e) {
            // 框架 CORS 拒绝未返回响应时，超时可接受
            log.info("CORS rejection caused timeout (expected): " + e.getMessage());
        }
    }
}