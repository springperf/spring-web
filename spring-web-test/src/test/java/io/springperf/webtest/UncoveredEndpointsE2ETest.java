package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * E2E: cover real controller endpoints previously not referenced by any E2E test.
 * <p>P0 controller: MultiValueMap param, RequestEntity, multi-method, multi-param/header
 * conditions; interceptor pass; upload ping.</p>
 */
public class UncoveredEndpointsE2ETest extends BaseE2ETest {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private String baseUrl() {
        return url("/api");
    }

    @Test
    void multiValueMap_getFirstValues() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p0/multi-value-map?a=1&b=2")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("1-2", resp.body().string());
        }
    }

    @Test
    void multiValueMap_missingValue_returnsNullSuffix() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p0/multi-value-map?a=only")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            // getFirst(missing) returns null, string concat yields "only-null"
            assertEquals("only-null", resp.body().string());
        }
    }

    @Test
    void requestEntity_receivesMethodAndBody() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p0/request-entity")
                .post(RequestBody.create("hello-entity", JSON))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            // 控制器返回格式固定（P0TestController#requestEntity），用精确等值替代两个 contains
            assertEquals("method=POST,body=hello-entity", resp.body().string(), "RequestEntity 应拿到完整请求方法与 body");
        }
    }

    @Test
    void multiMethod_getAndPostBothMatch() throws Exception {
        Request get = new Request.Builder().url(baseUrl() + "/p0/multi-method").get().build();
        try (Response resp = CLIENT.newCall(get).execute()) {
            assertEquals(200, resp.code());
            assertEquals("multi-method-ok", resp.body().string());
        }
        Request post = new Request.Builder().url(baseUrl() + "/p0/multi-method")
                .post(RequestBody.create("", JSON)).build();
        try (Response resp = CLIENT.newCall(post).execute()) {
            assertEquals(200, resp.code());
            assertEquals("multi-method-ok", resp.body().string(), "POST 分支应命中同一 handler 并返回相同结果");
        }
    }

    @Test
    void multiParam_allConditionsMatch() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p0/multi-param?a=1&b=2")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(), "all params conditions met should match");
            assertEquals("multi-param-matched", resp.body().string());
        }
    }

    @Test
    void multiParam_partialConditions_noMatch() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p0/multi-param?a=1")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code(), "params conditions are AND; missing one should not match");
        }
    }

    @Test
    void multiHeader_allConditionsMatch() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p0/multi-header")
                .header("X-A", "1")
                .header("X-B", "2")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(), "all header conditions met should match");
            assertEquals("multi-header-matched", resp.body().string());
        }
    }

    @Test
    void multiHeader_missingCondition_noMatch() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p0/multi-header")
                .header("X-A", "1")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code(), "headers conditions are AND; missing one should not match");
        }
    }

    @Test
    void interceptorRejectsLoginRequiredEndpoint() throws Exception {
        // LoginInterceptor 对方法名 "name" 抛 ResponseStatusException(UNAUTHORIZED)。
        // 端点本身返回固定串 "should be blocked"，因此必须反向断言它没有跑到 handler
        // （原用例断言控制器里硬编码的 intercepted=false，属恒真断言）。
        Request req = new Request.Builder().url(baseUrl() + "/core/name").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            String body = resp.body().string();
            assertEquals(401, resp.code(), "未登录访问受保护端点应 401，实际: " + resp.code() + " body=" + body);
            assertFalse(body.contains("should be blocked"), "拦截后 handler 不得执行，实际: " + body);
        }
    }

    @Test
    void uploadPing_returnsOk() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/upload/ping")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            Map<String, Object> result = com.alibaba.fastjson2.JSON.parseObject(body, Map.class);
            assertEquals("ok", result.get("status"), "/upload/ping 应返回 {\"status\":\"ok\"}，实际: " + body);
        }
    }
}