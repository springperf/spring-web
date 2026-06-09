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

import static org.junit.jupiter.api.Assertions.*;

public class HelloApiTest extends BaseE2ETest {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl = "http://localhost:9090/api/demo";

    @Test
    void hello_should_work() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/hello/123/aaab?v=netty")
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
                .url(baseUrl + "/find/hcd?v=111&id=777&age=33")
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
                .url(baseUrl + "/echo?received=" + received)
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
        Request req = new Request.Builder().url(baseUrl + "/echo").post(body).build();
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
                .url(baseUrl + "/read/hcd?name=111hcd")
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
        Request req = new Request.Builder().url(baseUrl + "/async").get().build();
        try (Response resp = shortClient.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertNotNull(resp.body().string());
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
                .url(baseUrl + "/upload")
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
        Request request = new Request.Builder().url(baseUrl + "/sse").get().build();
        CountDownLatch latch = new CountDownLatch(3);
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fail("SSE request failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                assertEquals(200, response.code());
                assertTrue(response.header("Content-Type").contains("text/event-stream"));
                BufferedSource source = response.body().source();
                while (!source.exhausted()) {
                    String line = source.readUtf8Line();
                    if (line == null) continue;
                    if (line.startsWith("data:")) {
                        latch.countDown();
                    }
                }
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Should receive at least 3 SSE events within timeout");
    }
}
