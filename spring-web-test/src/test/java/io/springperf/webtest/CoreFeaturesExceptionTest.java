package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CoreFeaturesExceptionTest extends BaseE2ETest {

    private String baseUrl() {
        return url("/api/core");
    }

    @Test
    void testControllerExceptionHandler() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/exception/controller-handler").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(500, resp.code());
            Map<String, Object> map = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("Controller exception occurred", map.get("error"));
            assertEquals("controller-handler", map.get("from"));
        }
    }

    @Test
    void testGlobalExceptionHandler() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/exception/illegal-argument").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(400, resp.code());
            Map<String, Object> map = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("bad argument", map.get("error"));
            assertEquals("global-handler", map.get("from"));
        }
    }

    @Test
    void testResponseStatusException() throws Exception {
        // 显式 Accept: application/json → ErrorPageRenderer 产出 JSON 错误体，可精确校验 status 字段
        Request req = new Request.Builder().url(baseUrl() + "/exception/response-status-exception")
                .header("Accept", "application/json").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(509, resp.code());
            String b = resp.body().string();
            assertEquals(509, com.alibaba.fastjson2.JSON.parseObject(b).getIntValue("status"),
                    "错误体应标明 509，实际: " + b);
        }
    }

    @Test
    void testCustomStatusException() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/exception/custom-status-exception")
                .header("Accept", "application/json").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
            String b = resp.body().string();
            assertEquals(404, com.alibaba.fastjson2.JSON.parseObject(b).getIntValue("status"),
                    "错误体应标明 404，实际: " + b);
        }
    }

    @Test
    void testFailingExceptionHandler_returns500() throws Exception {
        // @ExceptionHandler 方法自身抛出异常 → invokeAndWriteError catch → 500
        Request req = new Request.Builder()
                .url(baseUrl() + "/exception/failing-handler")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(500, resp.code(),
                    "When @ExceptionHandler itself throws, framework should return 500");
        }
    }
}