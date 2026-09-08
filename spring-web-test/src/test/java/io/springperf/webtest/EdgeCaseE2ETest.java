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
 * E2E 杈圭晫鍦烘櫙锛欻TTP 鏂规硶瀹屾暣鎬с€佺姸鎬佺爜銆佷腑鏂囩紪鐮併€佸鍊煎弬鏁般€?04/405 鍖哄垎銆佹潯浠惰姹?304銆並eep-Alive銆?
 */
public class EdgeCaseE2ETest extends BaseE2ETest {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private String edge() {
        return url("/api/edge");
    }

    // ==================== HTTP 鏂规硶瀹屾暣鎬?====================

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
            assertTrue(body == null || body.isEmpty(), "HEAD 鍝嶅簲涓嶅簲鍖呭惈 body锛屽疄闄? " + body);
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

    // ==================== 405 鏂规硶涓嶅尮閰?====================

    @Test
    void onlyGet_withPost_returns405() throws Exception {
        Request req = new Request.Builder().url(edge() + "/only-get")
                .post(RequestBody.create("", JSON_TYPE)).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(405, resp.code(), "浠?GET 鐨勭鐐规敹鍒?POST 搴旇繑鍥?405");
        }
    }

    @Test
    void onlyGet_withDelete_returns405() throws Exception {
        Request req = new Request.Builder().url(edge() + "/only-get").delete().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(405, resp.code(), "浠?GET 鐨勭鐐规敹鍒?DELETE 搴旇繑鍥?405");
        }
    }

    @Test
    void nonexistentPath_returns404() throws Exception {
        Request req = new Request.Builder().url(edge() + "/does-not-exist").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code(), "涓嶅瓨鍦ㄧ殑璺緞搴旇繑鍥?404");
        }
    }

    // ==================== 鐘舵€佺爜 ====================

    @Test
    void noContent_returns204() throws Exception {
        Request req = new Request.Builder().url(edge() + "/no-content")
                .post(RequestBody.create("", JSON_TYPE)).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(204, resp.code());
            String body = resp.body() != null ? resp.body().string() : null;
            assertTrue(body == null || body.isEmpty(), "204 涓嶅簲鏈?body");
        }
    }

    @Test
    void dynamicStatus_returnsConfiguredCode() throws Exception {
        for (int code : new int[]{201, 202, 400, 404, 500}) {
            Request req = new Request.Builder().url(edge() + "/status/" + code).get().build();
            try (Response resp = CLIENT.newCall(req).execute()) {
                assertEquals(code, resp.code(), "鐘舵€佺爜 " + code + " 搴旈€忎紶");
            }
        }
    }

    // ==================== 涓枃/UTF-8 ====================

    @Test
    void chineseResponse_returnsUtf8() throws Exception {
        Request req = new Request.Builder().url(edge() + "/chinese?name=灏忔槑").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String raw = resp.body().string();
            Map<String, Object> body = JSON.parseObject(raw, Map.class);
            assertEquals("浣犲ソ锛屼笘鐣?, body.get("greeting"));
            assertEquals("灏忔槑", body.get("name"));
        }
    }

    @Test
    void chineseRequestBody_roundTrips() throws Exception {
        String payload = "{\"message\":\"涓枃鍐呭娴嬭瘯\"}";
        Request req = new Request.Builder().url(edge() + "/chinese-body")
                .post(RequestBody.create(payload.getBytes(StandardCharsets.UTF_8), JSON_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> received = (Map<String, Object>) body.get("received");
            assertEquals("涓枃鍐呭娴嬭瘯", received.get("message"));
        }
    }

    // ==================== @RequestParam 澶氬€?====================

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

    // ==================== 璺緞鍙橀噺 ====================

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
