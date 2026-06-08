package io.springperf.webtest.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P4 E2E 测试：produces/consumes 正条件、@CrossOrigin、DeferredResult、
 * {@code @ResponseStatus} 异常、@CookieValue、三级类继承、多 Filter 排序。
 * <p>
 * 与 ProxyE2eTest 共享 Spring 上下文（端口 9092）。
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
public class ProxyP4E2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType XML_TYPE = MediaType.parse("application/xml; charset=utf-8");
    private static final MediaType TEXT_TYPE = MediaType.parse("text/plain; charset=utf-8");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl = "http://localhost:9092/api";

    // ==================== 1. produces 正条件 ====================

    @Test
    void producesJson_withAcceptJson_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p4/json-only")
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = MAPPER.readValue(resp.body().string(), Map.class);
            assertEquals("json", body.get("format"));
        }
    }

    @Test
    void producesJson_withAcceptXml_returns404() throws Exception {
        // produces = "application/json" 不匹配 Accept: text/xml
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p4/json-only")
                .header("Accept", "text/xml")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ==================== 2. consumes 正条件 ====================

    @Test
    void consumesJson_withJsonContentType_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p4/consume-json")
                .post(RequestBody.create("{\"key\":\"val\"}", JSON_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String raw = resp.body().string();
            Map<String, Object> body = MAPPER.readValue(raw, Map.class);
            assertEquals(true, body.get("accepted"));
        }
    }

    @Test
    void consumesJson_withTextContentType_returns404() throws Exception {
        // consumes = "application/json" 不匹配 Content-Type: text/plain
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p4/consume-json")
                .post(RequestBody.create("hello", TEXT_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ==================== 3. @CrossOrigin ====================

    @Test
    void cors_withMatchingOrigin_returnsAllowOriginHeader() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p4/cors")
                .header("Origin", "https://example.com")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String allowOrigin = resp.header("Access-Control-Allow-Origin");
            assertEquals("https://example.com", allowOrigin,
                    "Matching origin should get Access-Control-Allow-Origin header");
        }
    }

    // ==================== 4. DeferredResult 异步 ====================

    @Test
    void asyncDeferredResult_returnsDone() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p4/async")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String raw = resp.body().string();
            Map<String, Object> body = MAPPER.readValue(raw, Map.class);
            assertEquals("done", body.get("async"));
        }
    }

    // ==================== 5. @ResponseStatus 异常 ====================

    @Test
    void blockedResource_returns429() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p4/blocked-resource")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(429, resp.code(),
                    "@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS) should map to 429");
        }
    }

    // ==================== 6. 三级类继承 (GrandchildController) ====================

    @Test
    void grandchildController_threeLevelInheritance_returnsGrandchildStatus() throws Exception {
        // GrandchildController extends ChildController extends ParentController
        // 类级 @RequestMapping("/proxy-parent") 通过 AnnotatedElementUtils 三级继承
        // GrandchildController 的 /grandchild-status 应注册为 /proxy-parent/grandchild-status
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-parent/grandchild-status")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("grandchild-ok", resp.body().string());
        }
    }

    // ==================== 7. 多 Filter 排序 ====================

    @Test
    void filters_executedInOrder_returnsBothFilterHeaders() throws Exception {
        // ProxyTestFilter  @Order(1)  → 添加 X-Test-Filter
        // ProxyTestFilter2 @Order(2)  → 添加 X-Test-Filter2
        // BlockingProxyFilter @Order(20) → 仅阻断特定路径
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-p4/json-only")
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("executed", resp.header("X-Test-Filter"),
                    "Filter @Order(1) should be executed");
            assertEquals("executed", resp.header("X-Test-Filter2"),
                    "Filter @Order(2) should be executed");
        }
    }
}