package io.springperf.webtest;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

public class CorsAndStaticTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api";

    @Test
    void testCorsPreflight() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/core/cors/annotated")
                .header("Origin", "http://example.com")
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", null)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            int code = resp.code();
            assertTrue(code == 200 || code == 204,
                    "Expected 200 or 204 for CORS preflight, got " + code);
            // 验证 CORS 响应头
            String allowOrigin = resp.header("Access-Control-Allow-Origin");
            assertNotNull(allowOrigin, "CORS preflight must include Access-Control-Allow-Origin");
            assertTrue(allowOrigin.contains("http://example.com"),
                    "Access-Control-Allow-Origin should contain the allowed origin");
            assertNotNull(resp.header("Access-Control-Allow-Methods"),
                    "CORS preflight must include Access-Control-Allow-Methods");
            assertNotNull(resp.header("Vary"),
                    "CORS preflight must include Vary header");
        }
    }

    @Test
    void testCorsAnnotation() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/core/cors/annotated")
                .header("Origin", "http://example.com")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = Objects.toString(resp.body().string(), "");
            assertTrue(body.contains("cors"));
        }
    }

    @Test
    void testStaticResource() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/static/test.txt").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("Hello, Static Resource!"));
        }
    }

    @Test
    void testStaticResourceNotFound() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/static/nonexistent.txt").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }
}