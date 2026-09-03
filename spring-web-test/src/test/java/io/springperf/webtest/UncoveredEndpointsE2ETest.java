package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
            String body = resp.body().string();
            assertTrue(body.contains("method=POST"), "should contain method, got: " + body);
            assertTrue(body.contains("hello-entity"), "should contain body, got: " + body);
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
    void interceptorPass_returnsInterceptedFalse() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/core/interceptor/pass")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = com.alibaba.fastjson2.JSON.parseObject(resp.body().string(), Map.class);
            assertEquals(false, body.get("intercepted"));
        }
    }

    @Test
    void uploadPing_returnsOk() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/upload/ping")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertNotNull(resp.body().string());
        }
    }
}