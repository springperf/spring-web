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
        String expectedData = JSON.parseObject(LARGE_BODY).getString("data");
        Request req = new Request.Builder().url(url("/api/demo/echo")).post(RequestBody.create(JSON_MEDIA, LARGE_BODY))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(201, resp.code());
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals(true, map.get("received"));
            assertEquals(10240, map.get("count"));
            assertEquals(expectedData, map.get("data"), "100KB data 必须逐字节完整回显，实际长度: "
                    + String.valueOf(map.get("data")).length());
        }
    }

    @Test
    void largePost_concurrent_shouldSucceed() throws Exception {
        // 4 线程并发发送大请求，模拟 JMH 行为
        int threadCount = 4;
        int requestsPerThread = 5;
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicReference<Throwable> firstError = new java.util.concurrent.atomic.AtomicReference<>();
        String expectedData = JSON.parseObject(LARGE_BODY).getString("data");

        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < requestsPerThread; i++) {
                    try {
                        Request req = new Request.Builder()
                                .url(url("/api/demo/echo"))
                                .post(RequestBody.create(JSON_MEDIA, LARGE_BODY))
                                .build();
                        try (Response resp = CLIENT.newCall(req).execute()) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> map = JSON.parseObject(resp.body().string(), Map.class);
                            if (resp.code() != 201 || !Integer.valueOf(10240).equals(map.get("count"))
                                    || !expectedData.equals(map.get("data"))) {
                                failures.incrementAndGet();
                            }
                        }
                    } catch (Throwable e) {
                        firstError.compareAndSet(null, e);
                        failures.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        org.junit.jupiter.api.Assertions.assertNull(firstError.get(), "并发请求不得抛异常: " + firstError.get());
        assertEquals(0, failures.get(), "20 个并发大请求都应返回 201 且 data 完整");
    }
}