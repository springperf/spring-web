package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.*;
import okio.BufferedSource;
import org.junit.jupiter.api.Test;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class HelloApiTest extends BaseE2ETest {

    public String baseUrl = "http://localhost:9090/api/demo";

    @Test
    void hello_should_work() throws Exception {
        HttpUrl url = HttpUrl.parse(baseUrl + "/hello/123/aaab")
                .newBuilder()
                .addQueryParameter("v", "netty")
                .build();

        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String result = resp.body().string();
            log.info(result);
            assertEquals("{\"v\":\"netty\",\"message\":\"hello 123\"}", result);
        }
    }

    @Test
    void testPostJson() throws Exception {
        RequestBody body = RequestBody.create(
                "{\"tid\": 1243456,\"data\":{\"name\":\"123cd\",\"age\":122}}",
                MediaType.parse("application/json")
        );

        Request req = new Request.Builder()
                .url(baseUrl + "/find/hcd?v=111&id=777&age=33")
                .post(body)
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> result = JSON.parseObject(resp.body().string(), Map.class);
            log.info(String.valueOf(result));
            assertTrue("1243456".equals(result.get("tid").toString()));
        }
    }

    @Test
    void echoGet() throws Exception {
        String received = System.currentTimeMillis() + "test";
        Request req = new Request.Builder().url(baseUrl + "/echo?received=" + received).get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(302, resp.code());
            String result = resp.body().string();
            log.info(result);
            assertEquals(received, result);
        }
    }

    @Test
    void echoPost() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("tid", "1243456");
        data.put("name", "123cd");
        data.put("age", "33");
        RequestBody body = RequestBody.create(JSON.toJSONString(data), MediaType.parse("application/json"));

        Request req = new Request.Builder().url(baseUrl + "/echo").post(body).build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(201, resp.code());
            data.put("received", true);
            String result = resp.body().string();
            log.info(result);
            Map<String, String> map = JSON.parseObject(result, Map.class);
            assertTrue(data.equals(map));
        }
    }

    @Test
    void testPostForm() throws Exception {
        RequestBody body = new FormBody.Builder().add("age", "111")
                .add("ids", "aaa").add("ids", "bbb").build();
        Request req = new Request.Builder()
                .url(baseUrl + "/read/hcd?name=111hcd")
                .post(body)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> result = JSON.parseObject(resp.body().string(), Map.class);
            log.info(String.valueOf(result));
            assertTrue("hcd".equals(result.get("name2").toString()));
            assertTrue("111".equals(result.get("age").toString()));
            assertTrue("111hcd".equals(result.get("name").toString()));
        }
    }

    @Test
    void asyncGet() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/async").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String result = resp.body().string();
            log.info(result);
        }
    }

    @Test
    void testPostFormData() throws Exception {
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("userPart", "{\"name\":\"123cd\",\"age\":122}")
                .addFormDataPart("file", "testFile.txt", RequestBody.create("aaaaaaaaaaaaaaaaaaaaaa".getBytes(StandardCharsets.UTF_8))).build();
        Request req = new Request.Builder()
                .url(baseUrl + "/upload")
                .addHeader("Content-Type", "multipart/form-data")
                .post(body)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> result = JSON.parseObject(resp.body().string(), Map.class);
            log.info(String.valueOf(result));
            assertTrue("testFile.txt".equals(result.get("fileName").toString()));
            assertTrue("123cd".equals(result.get("name").toString()));
        }
    }

    @Test
    void testSse() throws InterruptedException {
        Request request = new Request.Builder().url(baseUrl + "/sse").get().build();
        CountDownLatch latch = new CountDownLatch(100);
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("SSE request failed: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                assertEquals(200, response.code());
                assertTrue(response.header("Content-Type")
                        .contains("text/event-stream"));
                BufferedSource source = response.body().source();
                while (!source.exhausted()) {
                    String line = source.readUtf8Line();
                    if (line == null) {
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        log.info(line.substring(5).trim());
                        latch.countDown();
                    } else {
                        log.info(line);
                    }
                }
            }
        });
        boolean completed = latch.await(10, TimeUnit.SECONDS);

        // 必须在超时时间内收到足够事件
        assertTrue(completed, "Did not receive expected SSE events in time");
    }
}
