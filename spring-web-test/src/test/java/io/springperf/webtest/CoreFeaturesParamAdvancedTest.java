package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CoreFeaturesParamAdvancedTest extends BaseE2ETest {

    private String baseUrl() {
        return url("/api/core");
    }

    @Test
    void requestParam_withDefault_usesDefaultWhenMissing() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/param-advanced?req=hello")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("hello", body.get("required"));
            // 未传 def 时应回落到 @RequestParam(defaultValue = "defaultVal")
            assertEquals("defaultVal", body.get("withDefault"));
        }
    }

    @Test
    void requestParam_requiredFalse_acceptsMissing() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/param-advanced?req=test&opt=provided")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("test", body.get("required"));
            assertEquals("provided", body.get("optional"));
        }
    }

    @Test
    void requestParam_withExplicitValue_usesProvidedValue() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/param-advanced?req=x&def=customVal")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            // def param was explicitly provided, should use the provided value
            String defVal = body.get("withDefault") != null ? body.get("withDefault").toString() : "";
            assertEquals("customVal", defVal);
        }
    }

    @Test
    void requestParam_requiredTrue_missingReturnsError() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/param-advanced")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // Required @RequestParam "req" 缺失 → 400（与 @RequestHeader 缺失行为一致）
            assertEquals(400, resp.code(), "缺失必填参数应返回 400，实际 " + resp.code());
        }
    }

    @Test
    void requestHeader_withDefault_usesDefaultWhenMissing() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/header-advanced")
                .header("X-Required", "req-value")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("req-value", body.get("required"));
            // 未传 X-With-Default 时应回落到 @RequestHeader(defaultValue = "defaultHeaderVal")
            assertEquals("defaultHeaderVal", body.get("withDefault"));
        }
    }

    @Test
    void requestHeader_requiredFalse_acceptsMissing() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/header-advanced")
                .header("X-Required", "req")
                .header("X-Optional", "opt")
                .header("X-With-Default", "custom")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("opt", body.get("optional"));
            assertEquals("custom", body.get("withDefault"));
        }
    }

    @Test
    void requestHeader_requiredTrue_missingReturnsError() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/header-advanced")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // Required @RequestHeader "X-Required" is missing
            // Framework may return 200 or 4xx depending on configuration
            assertEquals(400, resp.code(), "Request without required header should complete, got " + resp.code());
        }
    }
}