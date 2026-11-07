package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.*;
import okio.BufferedSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class HelloApiTest extends BaseE2ETest {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private String baseUrl() {
        return url("/api/demo");
    }

    @Test
    void hello_should_work() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/hello/123/aaab?v=netty")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("{\"v\":\"netty\",\"message\":\"hello 123\"}", resp.body().string());
        }
    }

    @Test
    void testPostJson() throws Exception {
        RequestBody body = RequestBody.create(
                "{\"tid\": 1243456,\"data\":{\"name\":\"123cd\",\"age\":122}}", JSON_MEDIA);
        Request req = new Request.Builder()
                .url(baseUrl() + "/find/hcd?v=111&id=777&age=33")
                .post(body)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> result = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("1243456", Objects.toString(result.get("tid")));
        }
    }

    @Test
    void echoGet() throws Exception {
        String received = System.currentTimeMillis() + "test";
        Request req = new Request.Builder()
                .url(baseUrl() + "/echo?received=" + received)
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(302, resp.code());
            assertEquals(received, resp.body().string());
        }
    }

    @Test
    void echoPost() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("tid", "1243456");
        data.put("name", "123cd");
        data.put("age", "33");
        RequestBody body = RequestBody.create(JSON.toJSONString(data), JSON_MEDIA);
        Request req = new Request.Builder().url(baseUrl() + "/echo").post(body).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(201, resp.code());
            data.put("received", true);
            String result = resp.body().string();
            Map<String, String> map = JSON.parseObject(result, Map.class);
            assertEquals(data, map);
        }
    }

    @Test
    void testPostForm() throws Exception {
        RequestBody body = new FormBody.Builder()
                .add("age", "111")
                .add("ids", "aaa").add("ids", "bbb")
                .build();
        Request req = new Request.Builder()
                .url(baseUrl() + "/read/hcd?name=111hcd")
                .post(body)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> result = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("hcd", Objects.toString(result.get("name2")));
            assertEquals("111", Objects.toString(result.get("age")));
            assertEquals("111hcd", Objects.toString(result.get("name")));
        }
    }

    @Test
    void asyncGet() throws Exception {
        OkHttpClient shortClient = CLIENT.newBuilder()
                .readTimeout(Duration.ofSeconds(5))
                .build();
        Request req = new Request.Builder().url(baseUrl() + "/async").get().build();
        try (Response resp = shortClient.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            Map<String, Object> result = JSON.parseObject(body, Map.class);
            assertTrue(result.get("now") instanceof Number, "/async 应返回 {\"now\":<时间戳>}，实际: " + body);
        }
    }

    @Test
    void testPostFormData() throws Exception {
        RequestBody fileBody = RequestBody.create(
                "aaaaaaaaaaaaaaaaaaaaaa".getBytes(StandardCharsets.UTF_8));
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("userPart", "{\"name\":\"123cd\",\"age\":122}")
                .addFormDataPart("file", "testFile.txt", fileBody)
                .build();
        Request req = new Request.Builder()
                .url(baseUrl() + "/upload")
                .post(body)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> result = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("testFile.txt", Objects.toString(result.get("fileName")));
            assertEquals("123cd", Objects.toString(result.get("name")));
        }
    }

    @Test
    void testSse() throws Exception {
        Request request = new Request.Builder().url(baseUrl() + "/sse").get().build();
        assertSseFrames(request, "world + ");
    }

    @Test
    void testSseFast() throws Exception {
        // 测试 TaskExecutor 快速发送场景（0间隔），验证 complete() before initialize() 竞态已修复
        Request request = new Request.Builder().url(baseUrl() + "/sse-fast").get().build();
        assertSseFrames(request, "fast-");
    }

    /** 回调线程内的 JUnit 断言失败只会表现为超时，故收集全部帧后由主线程精确断言。 */
    private void assertSseFrames(Request request, String expectedPrefix) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ConcurrentLinkedQueue<String> frames = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.atomic.AtomicInteger status = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> contentType = new java.util.concurrent.atomic.AtomicReference<>();
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                failure.set(e);
                done.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response resp = response) {
                    status.set(resp.code());
                    contentType.set(resp.header("Content-Type"));
                    BufferedSource source = resp.body().source();
                    while (!source.exhausted()) {
                        String line = source.readUtf8Line();
                        if (line != null && line.startsWith("data:")) {
                            frames.add(unquote(line.substring(5).trim()));
                        }
                    }
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "SSE 应在超时前结束");
        assertNull(failure.get(), "SSE 请求不得失败: " + failure.get());
        assertEquals(200, status.get());
        assertTrue(contentType.get() != null && contentType.get().contains("text/event-stream"),
                "SSE Content-Type 应为 text/event-stream，实际: " + contentType.get());
        assertEquals(10, frames.size(), "应收到全部 10 帧，实际帧: " + frames);
        int i = 0;
        for (String frame : frames) {
            assertEquals(expectedPrefix + i, frame, "第 " + i + " 帧内容或顺序不正确，实际帧: " + frames);
            i++;
        }
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }
}
