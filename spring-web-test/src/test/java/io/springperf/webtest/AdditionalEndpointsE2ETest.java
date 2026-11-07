package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: cover real endpoints not previously referenced: @InitBinder binding,
 * controller-level exception handlers, Locale resolution, @Optimize invocation,
 * resource download, interceptor return-false.
 */
public class AdditionalEndpointsE2ETest extends BaseE2ETest {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private String baseUrl() {
        return url("/api");
    }

    @Test
    void binderTest_appliesInitBinderPropertyEditor() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/binder/test?value=raw")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = com.alibaba.fastjson2.JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("edited:raw", body.get("value"),
                    "@InitBinder 的 PropertyEditor 应改写字段值，实际: " + body.get("value"));
        }
    }

    @Test
    void binderTest_missingValue_fieldNull() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/binder/test")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = com.alibaba.fastjson2.JSON.parseObject(resp.body().string(), Map.class);
            assertNull(body.get("value"));
        }
    }

    @Test
    void controllerException_handlerReturns500WithFrom() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/core/exception/controller-handler")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(500, resp.code());
            Map<String, Object> body = com.alibaba.fastjson2.JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("controller-handler", body.get("from"));
            assertEquals("Controller exception occurred", body.get("error"));
        }
    }

    @Test
    void responseStatusException_returnsBandwidthExceeded() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/core/exception/response-status-exception")
                .header("Accept", "application/json").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(509, resp.code(), "ResponseStatusException 应透传其状态码");
            String body = resp.body().string();
            assertEquals(509, com.alibaba.fastjson2.JSON.parseObject(body).getIntValue("status"),
                    "错误体应标明 509，实际: " + body);
        }
    }

    @Test
    void customStatusException_returnsAnnotatedStatus() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/core/exception/custom-status-exception")
                .header("Accept", "application/json").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code(), "@ResponseStatus 自定义异常应返回其状态码");
            String body = resp.body().string();
            assertEquals(404, com.alibaba.fastjson2.JSON.parseObject(body).getIntValue("status"),
                    "错误体应标明 404，实际: " + body);
        }
    }

    @Test
    void failingExceptionHandler_returns500() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/core/exception/failing-handler")
                .header("Accept", "application/json").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(500, resp.code(), "@ExceptionHandler 自身抛异常应最终回退 500");
            String body = resp.body().string();
            assertEquals(500, com.alibaba.fastjson2.JSON.parseObject(body).getIntValue("status"),
                    "错误体应标明 500，实际: " + body);
        }
    }

    @Test
    void locale_resolvesFromAcceptLanguage() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p0/locale")
                .header("Accept-Language", "fr-FR, fr;q=0.9, en;q=0.8")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            // 框架 LocaleConfig 默认 accept-header 策略：带 Accept-Language 时按请求首选 Locale 注入，
            // 控制器返回 locale.toLanguageTag()。原先"仅验证非空"的断言等于没测。
            String body = resp.body().string();
            assertEquals("fr-FR", body, "应解析 Accept-Language 的首选 Locale，实际: " + body);
        }
    }

    @Test
    void locale_noHeader_usesDefault() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p0/locale")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals(Locale.getDefault().toLanguageTag(), body,
                    "无 Accept-Language 时应回退 JVM 默认 Locale，实际: " + body);
        }
    }

    @Test
    void optimizeCheck_invokesWithOptimizer() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p1/optimize-check")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = com.alibaba.fastjson2.JSON.parseObject(resp.body().string(), Map.class);
            assertEquals(true, body.get("optimized"));
        }
    }

    @Test
    void downloadResource_returnsContent() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p1/download-resource")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("Hello, Static Resource!"),
                    "资源下载应返回文件内容，实际: " + body);
        }
    }

    @Test
    void interceptorReturnFalse_returns200EmptyBody() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p0/interceptor-return-false")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(), "拦截器返回 false 时应写 200 空 body");
            String body = resp.body() != null ? resp.body().string() : null;
            assertTrue(body == null || body.isEmpty(), "应无业务 body，实际: " + body);
            assertFalse(body != null && body.contains("should-not-be-reached"),
                    "拦截器返回 false 时 handler 不得执行，实际: " + body);
        }
    }
}