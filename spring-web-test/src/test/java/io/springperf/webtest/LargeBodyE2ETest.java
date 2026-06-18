package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 100KB 大请求体 POST 正常工作。
 */
public class LargeBodyE2ETest extends BaseE2ETest {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private static final String LARGE_BODY;

    static {
        StringBuilder sb = new StringBuilder(105 * 1024);
        sb.append("{\"data\":\"");
        for (int i = 0; i < 10240; i++) {
            sb.append("abcdefghij");
        }
        sb.append("\",\"count\":10240}");
        LARGE_BODY = sb.toString();
    }

    @Test
    void largePost_shouldReturn201() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/demo/echo")
                .post(RequestBody.create(JSON_MEDIA, LARGE_BODY))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            System.out.println("Status: " + resp.code());
            String body = resp.body().string();
            System.out.println("Body length: " + body.length());
            assertEquals(201, resp.code());

            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.parseObject(body, Map.class);
            assertTrue(map.containsKey("received"));
            assertEquals(true, map.get("received"));
            assertEquals(10240, map.get("count"));
        }
    }

    @Test
    void largePost_concurrent_shouldSucceed() throws Exception {
        // 4 线程并发发送大请求，模拟 JMH 行为
        int threadCount = 4;
        int requestsPerThread = 5;
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);

        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < requestsPerThread; i++) {
                    try {
                        Request req = new Request.Builder()
                                .url("http://localhost:9090/api/demo/echo")
                                .post(RequestBody.create(JSON_MEDIA, LARGE_BODY))
                                .build();
                        try (Response resp = CLIENT.newCall(req).execute()) {
                            if (resp.code() != 201) {
                                System.err.println("FAIL: " + resp.code() + " " + resp.body().string());
                                failures.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("EXCEPTION: " + e.getMessage());
                        failures.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(0, failures.get(), "Concurrent large POST requests should all succeed");
    }
}