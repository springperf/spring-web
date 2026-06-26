package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class CoreFeaturesStreamTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api/core";

    @Test
    void testStreamJson() throws Exception {
        Request request = new Request.Builder().url(baseUrl + "/stream-json").get().build();
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger count = new AtomicInteger(0);
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fail("Stream request failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                assertEquals(200, response.code());
                BufferedSource source = response.body().source();
                while (!source.exhausted()) {
                    String line = source.readUtf8Line();
                    if (line == null || line.isEmpty()) {
                        continue;
                    }
                    try {
                        Map<String, Object> m = JSON.parseObject(line, Map.class);
                        if (m != null && m.containsKey("index")) {
                            count.incrementAndGet();
                            latch.countDown();
                        }
                    } catch (Exception ignored) {
                        // skip non-json lines
                    }
                }
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Did not receive all stream JSON events");
        assertEquals(3, count.get());
    }

    @Test
    void testTextStream() throws Exception {
        Request request = new Request.Builder().url(baseUrl + "/text-stream").get().build();
        CountDownLatch latch = new CountDownLatch(2);
        StringBuilder sb = new StringBuilder();
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fail("Text stream request failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                assertEquals(200, response.code());
                BufferedSource source = response.body().source();
                while (!source.exhausted()) {
                    String line = source.readUtf8Line();
                    if (line == null) {
                        continue;
                    }
                    sb.append(line).append("\n");
                    latch.countDown();
                }
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Did not receive all text stream events");
        String result = sb.toString();
        assertTrue(result.contains("line1"));
        assertTrue(result.contains("line2"));
    }
}