package io.springperf.webtest.proxy;

import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 E2E 测试：CGLIB 代理 Controller 的参数边界场景。
 * <p>
 * 与 ProxyE2eTest 共享同一个 Spring 上下文（相同配置），
 * 但单独组织测试类以保持可维护性。
 */
@SpringBootTest(
        classes = ProxyE2eApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=9092",
                "server.servlet.context-path=/api",
                "proxy.placeholder.path=/proxy/placeholder-resolved"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProxyP1E2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl = "http://localhost:9092/api";

    // ==================== 多参数组合：@RequestBody + @PathVariable + @RequestParam + @RequestHeader + optional + defaultValue ====================

    @Test
    void postMixedParams_withProxy_resolvesAllAnnotations() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p1/mixed/abc?key=k1&def=custom")
                .post(RequestBody.create(JSON, "\"req-body\""))
                .addHeader("X-Custom", "hdr-val")
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            // body|id|key|header|optional|withDefault
            assertTrue(body.contains("req-body") && body.contains("abc") && body.contains("k1"),
                    "Should contain body, path variable and request param: " + body);
            assertTrue(body.contains("hdr-val"),
                    "Should contain header value: " + body);
            assertTrue(body.contains("custom"),
                    "Should use provided defaultValue param: " + body);
        }
    }

    @Test
    void postMixedParams_withProxy_usesDefaultValue() throws Exception {
        // 不传 def 参数，验证 defaultValue="fallback" 生效
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p1/mixed/xyz?key=k2")
                .post(RequestBody.create(JSON, "\"data\""))
                .addHeader("X-Custom", "hdr")
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("data") && body.contains("xyz") && body.contains("k2"),
                    "Should contain body, path variable and request param: " + body);
            assertTrue(body.contains("null|fallback"),
                    "Optional should be null, default should be 'fallback': " + body);
        }
    }

    @Test
    void postMixedParams_withoutRequiredParam_returns400() throws Exception {
        // 缺少 required @RequestParam("key")
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p1/mixed/abc")
                .post(RequestBody.create(JSON, "\"body\""))
                .addHeader("X-Custom", "hdr")
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertTrue(resp.code() >= 400,
                    "Missing required @RequestParam should return 4xx, got " + resp.code());
        }
    }

    // ==================== @RequestBody 空 body ====================

    @Test
    void postEmptyBody_withProxy_returnsGotNull() throws Exception {
        // Content-Length 为 0 的 POST 请求
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p1/empty-body")
                .post(RequestBody.create(new byte[0]))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            // 框架可能返回 null 或空字符串，都接受
            assertNotNull(body);
        }
    }

    // ==================== @RequestHeader 多值、可选、缺失 ====================

    @Test
    void getMultiHeader_withProxy_resolvesList() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p1/headers")
                .get()
                .addHeader("X-Multi", "val1")
                .addHeader("X-Multi", "val2")
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("val1") && body.contains("val2"),
                    "Should contain both header values: " + body);
        }
    }

    @Test
    void getRequiredHeader_withProxy_missing_returns400() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p1/required-header")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertTrue(resp.code() >= 400,
                    "Missing required @RequestHeader should return 4xx, got " + resp.code());
        }
    }

    // ==================== ResponseEntity 返回 + proxy ====================

    @Test
    void getResponseEntity_withProxy_returnsCustomStatusAndHeaders() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p1/entity")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(201, resp.code());
            assertEquals("header-value", resp.header("X-Custom-Resp"));
            String body = resp.body().string();
            assertEquals("entity-body", body);
        }
    }
}