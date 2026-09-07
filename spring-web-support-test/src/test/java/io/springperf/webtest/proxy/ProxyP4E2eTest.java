package io.springperf.webtest.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P4 E2E 娴嬭瘯锛歱roduces/consumes 姝ｆ潯浠躲€丂CrossOrigin銆丏eferredResult銆?
 * {@code @ResponseStatus} 寮傚父銆丂CookieValue銆佷笁绾х被缁ф壙銆佸 Filter 鎺掑簭銆?
 * <p>
 * 涓?ProxyE2eTest 鍏变韩 Spring 涓婁笅鏂囷紙绔彛 9092锛夈€?
 */
@SpringBootTest(
        classes = ProxyE2eApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
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


    @LocalServerPort
    private int serverPort;

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }
    private String baseUrl() {
        return url("/api");
    }

    // ==================== 1. produces 姝ｆ潯浠?====================

    @Test
    void producesJson_withAcceptJson_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p4/json-only")
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
        // produces = "application/json" 涓嶅尮閰?Accept: text/xml
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p4/json-only")
                .header("Accept", "text/xml")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ==================== 2. consumes 姝ｆ潯浠?====================

    @Test
    void consumesJson_withJsonContentType_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p4/consume-json")
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
        // consumes = "application/json" 涓嶅尮閰?Content-Type: text/plain
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p4/consume-json")
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
                .url(baseUrl() + "/proxy-p4/cors")
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

    // ==================== 4. DeferredResult 寮傛 ====================

    @Test
    void asyncDeferredResult_returnsDone() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p4/async")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String raw = resp.body().string();
            Map<String, Object> body = MAPPER.readValue(raw, Map.class);
            assertEquals("done", body.get("async"));
        }
    }

    // ==================== 5. @ResponseStatus 寮傚父 ====================

    @Test
    void blockedResource_returns429() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p4/blocked-resource")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(429, resp.code(),
                    "@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS) should map to 429");
        }
    }

    // ==================== 6. 涓夌骇绫荤户鎵?(GrandchildController) ====================

    @Test
    void grandchildController_threeLevelInheritance_returnsGrandchildStatus() throws Exception {
        // GrandchildController extends ChildController extends ParentController
        // 绫荤骇 @RequestMapping("/proxy-parent") 閫氳繃 AnnotatedElementUtils 涓夌骇缁ф壙
        // GrandchildController 鐨?/grandchild-status 搴旀敞鍐屼负 /proxy-parent/grandchild-status
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-parent/grandchild-status")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("grandchild-ok", resp.body().string());
        }
    }

    // ==================== 7. 澶?Filter 鎺掑簭 ====================

    @Test
    void filters_executedInOrder_returnsBothFilterHeaders() throws Exception {
        // ProxyTestFilter  @Order(1)  鈫?娣诲姞 X-Test-Filter
        // ProxyTestFilter2 @Order(2)  鈫?娣诲姞 X-Test-Filter2
        // BlockingProxyFilter @Order(20) 鈫?浠呴樆鏂壒瀹氳矾寰?
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p4/json-only")
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