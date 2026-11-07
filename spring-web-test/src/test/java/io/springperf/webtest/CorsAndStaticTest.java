package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

public class CorsAndStaticTest extends BaseE2ETest {

    private String baseUrl() {
        return url("/api");
    }

    @Test
    void testCorsPreflight() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/core/cors/annotated")
                .header("Origin", "http://example.com").header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", null).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(), "CORS 预检应返回确定状态码，实际: " + resp.code());
            assertEquals("http://example.com", resp.header("Access-Control-Allow-Origin"),
                    "ACAO 应精确回显允许的来源，实际: " + resp.headers());
            String methods = resp.header("Access-Control-Allow-Methods");
            // WebTestConfig 的全局注册（/**）与 @CrossOrigin(GET) 按 Spring 的 combine 语义取并集，
            // 所以允许方法是配置合并后的集合，而非单个 "GET"
            assertEquals("GET,POST,PUT,DELETE", methods, "预检应回写合并后的允许方法，实际: " + methods);
            assertNotNull(resp.header("Vary"), "CORS 预检必须包含 Vary header");
            assertTrue(String.join(",", resp.headers("Vary")).contains("Origin"),
                    "Vary 应包含 Origin，实际: " + resp.headers("Vary"));
        }
    }

    @Test
    void testCorsAnnotation() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/core/cors/annotated")
                .header("Origin", "http://example.com").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = Objects.toString(resp.body().string(), "");
            assertEquals("annotated", JSON.parseObject(body).get("cors"), "@CrossOrigin 端点应返回业务 JSON，实际: " + body);
            assertEquals("http://example.com", resp.header("Access-Control-Allow-Origin"),
                    "@CrossOrigin(origins=...) 应精确回显允许的来源，实际: " + resp.headers());
        }
    }

    @Test
    void testStaticResource() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/static/test.txt").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("Hello, Static Resource!"));
        }
    }

    @Test
    void testStaticResourceNotFound() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/static/nonexistent.txt").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }
}
