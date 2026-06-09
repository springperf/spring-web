package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 E2E 测试：核心框架遗漏的基础能力覆盖。
 * 依赖 CoreFeaturesController、P0TestController、NoRestControllerController。
 */
public class CoreFeaturesP0Test extends BaseE2ETest {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final String coreUrl = "http://localhost:9090/api/core";
    private final String p0Url = "http://localhost:9090/api/p0";
    private final String noRestUrl = "http://localhost:9090/api/p0/no-rest-controller";

    // ==================== 1. @RequestParam MultiValueMap ====================

    @Test
    void multiValueMap_withMultipleParams_bindsCorrectly() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/multi-value-map?a=hello&b=world")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("hello-world", resp.body().string());
        }
    }

    @Test
    void multiValueMap_withSingleParam_returnsNullForMissing() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/multi-value-map?a=only")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            // b 不存在时 getFirst 返回 null，字符串拼接为 "only-null"
            assertEquals("only-null", resp.body().string());
        }
    }

    // ==================== 2. Locale 参数解析 ====================

    @Test
    void locale_withAcceptLanguage_returnsParsedLocale() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/locale")
                .header("Accept-Language", "zh-CN")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            // 框架 LocaleResolver 解析 Accept-Language
            String body = resp.body().string();
            assertNotNull(body, "Locale should be resolved");
        }
    }

    @Test
    void locale_withoutAcceptLanguage_returnsDefaultLocale() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/locale")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertNotNull(resp.body().string(), "Default locale should be returned");
        }
    }

    // ==================== 3. @RequestMapping 多路径 ====================

    @Test
    void multiPath_firstPath_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/multi-path-a")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("multi-path-ok", resp.body().string());
        }
    }

    @Test
    void multiPath_secondPath_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/multi-path-b")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("multi-path-ok", resp.body().string());
        }
    }

    // ==================== 4. @RequestMapping method 数组 ====================

    @Test
    void multiMethod_getRequest_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/multi-method")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("multi-method-ok", resp.body().string());
        }
    }

    @Test
    void multiMethod_postRequest_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/multi-method")
                .post(RequestBody.create("", JSON_MEDIA))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("multi-method-ok", resp.body().string());
        }
    }

    // ==================== 5. @RequestMapping params 多条件（OR） ====================

    @Test
    void multiParamOr_firstConditionMatches_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/multi-param?a=1")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("multi-param-matched", resp.body().string());
        }
    }

    @Test
    void multiParamOr_secondConditionMatches_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/multi-param?b=2")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("multi-param-matched", resp.body().string());
        }
    }

    @Test
    void multiParamOr_neitherConditionMatches_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/multi-param?a=3&b=3")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ==================== 6. @RequestMapping headers 多条件（OR） ====================

    @Test
    void multiHeaderOr_firstConditionMatches_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/multi-header")
                .header("X-A", "1")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("multi-header-matched", resp.body().string());
        }
    }

    @Test
    void multiHeaderOr_secondConditionMatches_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/multi-header")
                .header("X-B", "2")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("multi-header-matched", resp.body().string());
        }
    }

    @Test
    void multiHeaderOr_neitherConditionMatches_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/multi-header")
                .header("X-A", "3")
                .header("X-B", "3")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ==================== 7. RequestEntity 参数 ====================

    @Test
    void requestEntity_withPost_containsMethodAndBody() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/request-entity")
                .post(RequestBody.create("\"test-body\"", JSON_MEDIA))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("POST"), "Should contain HTTP method");
            assertTrue(body.contains("test-body"), "Should contain request body");
        }
    }

    // ==================== 8. 控制器无 @RestController ====================

    @Test
    void noRestController_getWithParam_returnsValue() throws Exception {
        Request req = new Request.Builder()
                .url(noRestUrl + "/echo?msg=hello-no-rest")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("hello-no-rest", resp.body().string());
        }
    }

    @Test
    void noRestController_postWithBody_returns201() throws Exception {
        Request req = new Request.Builder()
                .url(noRestUrl + "/save")
                .post(RequestBody.create("\"data\"", JSON_MEDIA))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(201, resp.code());
            String body = resp.body().string();
            assertEquals("saved:\"data\"", body);
        }
    }

    // ==================== 9. Interceptor preHandle 返回 false ====================

    @Test
    void interceptorReturnFalse_responseHas200WithEmptyBody() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/interceptor-return-false")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            String body = resp.body().string();
            // 当 preHandle 返回 false 时，DispatcherHandler 直接 resp.flush() 返回，
            // controller 未执行，所以响应体不应包含 controller 返回的内容
            assertFalse(body.contains("should-not-be-reached"),
                    "Controller should NOT be reached when preHandle returns false, body: " + body);
            // Content-Length 应为 0
            String contentLength = resp.header("Content-Length");
            assertEquals("0", contentLength, "Content-Length should be 0 when preHandle returns false");
        }
    }

    @Test
    void interceptorReturnTrue_controllerExecutesNormally() throws Exception {
        Request req = new Request.Builder()
                .url(p0Url + "/interceptor-return-true")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("interceptor-allowed", resp.body().string());
        }
    }

    // ==================== 10. @RunInPool 线程名断言 ====================

    @Test
    void runInEventLoop_usesEventLoopThread() throws Exception {
        Request req = new Request.Builder()
                .url(coreUrl + "/pool/event-loop")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            String threadName = (String) body.get("thread");
            assertNotNull(threadName, "Thread name should be present");
            // Event loop 线程名通常包含 "nioEventLoopGroup"
            assertTrue(threadName.contains("nioEventLoopGroup")
                            || threadName.contains("eventLoop"),
                    "Event loop thread should contain event loop identifier, got: " + threadName);
        }
    }

    @Test
    void runInBizPool_usesBizPoolThread() throws Exception {
        Request req = new Request.Builder()
                .url(coreUrl + "/pool/biz-pool")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            String threadName = (String) body.get("thread");
            assertNotNull(threadName, "Thread name should be present");
            // Biz pool 线程名不应包含 event loop 标识
            assertFalse(threadName.contains("nioEventLoopGroup"),
                    "Biz pool thread should NOT be event loop thread, got: " + threadName);
        }
    }

    // ==================== 11. @RunInPool 不存在的池名 ====================

    @Test
    void runInNonExistentPool_returns500() throws Exception {
        Request req = new Request.Builder()
                .url(coreUrl + "/pool/bad-pool")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(500, resp.code());
        }
    }
}
