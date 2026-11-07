package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

public class CoreFeaturesParamReturnTest extends BaseE2ETest {

    private String baseUrl() {
        return url("/api/core");
    }

    @Test
    void testRequestHeader() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/header").header("X-Custom-Header", "my-value").get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = Objects.toString(resp.body().string(), "");
            assertEquals("my-value", com.alibaba.fastjson2.JSON.parseObject(body).getString("xCustomHeader"),
                    "应精确回显 X-Custom-Header 值，实际: " + body);
        }
    }

    @Test
    void testHttpEntity() throws Exception {
        RequestBody body = RequestBody.create("hello entity", MediaType.parse("text/plain"));
        Request req = new Request.Builder().url(baseUrl() + "/http-entity").post(body).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String responseBody = resp.body().string();
            assertTrue(responseBody.contains("received: hello entity"));
        }
    }

    @Test
    void testDeferredResultError() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/deferred-result-error").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(500, resp.code());
        }
    }

    @Test
    void testDeferredResult() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/deferred-result").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("ok", com.alibaba.fastjson2.JSON.parseObject(body).getString("status"),
                    "DeferredResult 应返回 {\"status\":\"ok\"}，实际: " + body);
        }
    }

    @Test
    void testCallable() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/callable").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("callable", com.alibaba.fastjson2.JSON.parseObject(body).getString("from"),
                    "Callable 应返回 {\"from\":\"callable\"}，实际: " + body);
        }
    }

    @Test
    void testListenableFuture() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/listenable-future").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("listenable-future-result"), "ListenableFuture 应返回异步结果，实际: " + body);
        }
    }

    @Test
    void testBytes() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/bytes").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("Hello, Bytes!", body);
        }
    }

    @Test
    void testResource() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/resource").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("text/plain", resp.header("Content-Type", "").split(";")[0].trim(),
                    "ClassPathResource 应返回 text/plain，实际: " + resp.header("Content-Type"));
            // 读取 body 验证资源内容（static/test.txt）
            String body = resp.body().string();
            assertEquals("Hello, Static Resource!", body.trim(), "Resource 应返回文件完整内容，实际: " + body);
        }
    }

    @Test
    void testInputStream() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/input-stream").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("Hello, Stream!", body);
        }
    }

    @Test
    void voidReturn_returns204() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/void")
                .post(RequestBody.create(MediaType.parse("text/plain"), "")).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(204, resp.code());
        }
    }
}
