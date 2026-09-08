package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E 边界场景：HTTP 方法完整性、状态码、中文编码、多值参数、404/405 区分、条件请求 304、Keep-Alive。
 */
public class EdgeCaseE2ETest extends BaseE2ETest {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private String edge() {
        return url("/api/edge");
    }

    // ==================== HTTP 方法完整性 ====================

    @Test
    void getMethod_returns200() throws Exception {
        try (Response resp = CLIENT.newCall(new Request.Builder().url(edge() + "/method").get().build()).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("GET", body.get("method"));
        }
    }

    @Test
    void postMethod_returns200() throws Exception {
        Request req = new Request.Builder().url(edge() + "/method")
                .post(RequestBody.create("", JSON_TYPE)).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("POST", body.get("method"));
        }
    }

    @Test
    void putMethod_returns200() throws Exception {
        Request req = new Request.Builder().url(edge() + "/method")
                .put(RequestBody.create("", JSON_TYPE)).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("PUT", body.get("method"));
        }
    }

    @Test
    void deleteMethod_returns200() throws Exception {
        Request req = new Request.Builder().url(edge() + "/method").delete().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("DELETE", body.get("method"));
        }
    }

    @Test
    void patchMethod_returns200() throws Exception {
        Request req = new Request.Builder().url(edge() + "/method")
                .patch(RequestBody.create("", JSON_TYPE)).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("PATCH", body.get("method"));
        }
    }

    @Test
    void headMethod_returnsHeadersWithoutBody() throws Exception {
        Request req = new Request.Builder().url(edge() + "/method").head().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body() != null ? resp.body().string() : null;
            assertTrue(body == null || body.isEmpty(), "HEAD 响应不应包含 body，实际: " + body);
        }
    }

    @Test
    void methodAll_matchesAnyMethod() throws Exception {
        Request get = new Request.Builder().url(edge() + "/method-all").get().build();
        try (Response resp = CLIENT.newCall(get).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("ALL", body.get("method"));
        }
        Request post = new Request.Builder().url(edge() + "/method-all")
                .post(RequestBody.create("", JSON_TYPE)).build();
        try (Response resp = CLIENT.newCall(post).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("ALL", body.get("method"));
        }
    }

    // ==================== 405 方法不匹配 ====================

    @Test
    void onlyGet_withPost_returns405() throws Exception {
        Request req = new Request.Builder().url(edge() + "/only-get")
                .post(RequestBody.create("", JSON_TYPE)).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(405, resp.code(), "仅 GET 的端点收到 POST 应返回 405");
        }
    }

    @Test
    void onlyGet_withDelete_returns405() throws Exception {
        Request req = new Request.Builder().url(edge() + "/only-get").delete().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(405, resp.code(), "仅 GET 的端点收到 DELETE 应返回 405");
        }
    }

    @Test
    void nonexistentPath_returns404() throws Exception {
        Request req = new Request.Builder().url(edge() + "/does-not-exist").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code(), "不存在的路径应返回 404");
        }
    }

    // ==================== 状态码 ====================

    @Test
    void noContent_returns204() throws Exception {
        Request req = new Request.Builder().url(edge() + "/no-content")
                .post(RequestBody.create("", JSON_TYPE)).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(204, resp.code());
            String body = resp.body() != null ? resp.body().string() : null;
            assertTrue(body == null || body.isEmpty(), "204 不应有 body");
        }
    }

    @Test
    void dynamicStatus_returnsConfiguredCode() throws Exception {
        for (int code : new int[]{201, 202, 400, 404, 500}) {
            Request req = new Request.Builder().url(edge() + "/status/" + code).get().build();
            try (Response resp = CLIENT.newCall(req).execute()) {
                assertEquals(code, resp.code(), "状态码 " + code + " 应透传");
            }
        }
    }

    // ==================== 中文/UTF-8 ====================

    @Test
    void chineseResponse_returnsUtf8() throws Exception {
        Request req = new Request.Builder().url(edge() + "/chinese?name=小明").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String raw = resp.body().string();
            Map<String, Object> body = JSON.parseObject(raw, Map.class);
            assertEquals("你好，世界", body.get("greeting"));
            assertEquals("小明", body.get("name"));
        }
    }

    @Test
    void chineseRequestBody_roundTrips() throws Exception {
        String payload = "{\"message\":\"中文内容测试\"}";
        Request req = new Request.Builder().url(edge() + "/chinese-body")
                .post(RequestBody.create(payload.getBytes(StandardCharsets.UTF_8), JSON_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> received = (Map<String, Object>) body.get("received");
            assertEquals("中文内容测试", received.get("message"));
        }
    }

    // ==================== @RequestParam 多值 ====================

    @Test
    void multiParam_arrayAndList() throws Exception {
        Request req = new Request.Builder()
                .url(edge() + "/multi-param?ids=1&ids=2&ids=3&tags=a&tags=b")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals(3, body.get("ids"));
            assertEquals("1", body.get("first"));
            assertEquals(Arrays.asList("a", "b"), body.get("tags"));
        }
    }

    @Test
    void multiParam_singleValue() throws Exception {
        Request req = new Request.Builder()
                .url(edge() + "/multi-param?ids=only")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals(1, body.get("ids"));
            assertEquals("only", body.get("first"));
        }
    }

    // ==================== 路径变量 ====================

    @Test
    void pathVariable_injected() throws Exception {
        Request req = new Request.Builder().url(edge() + "/path-var/abc123").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("abc123", body.get("id"));
        }
    }
}
