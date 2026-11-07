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

    private String baseUrl() {
        return url("/api/core");
    }

    @Test
    void testStreamJson() throws Exception {
        Request request = new Request.Builder().url(baseUrl() + "/stream-json").get().build();
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ConcurrentLinkedQueue<Integer> indices = new java.util.concurrent.ConcurrentLinkedQueue<>();
        AtomicInteger status = new AtomicInteger();
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                failure.set(e);
                done.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) {
                // 回调线程里的断言失败只会变成超时，统一收集结果由主线程断言
                try (Response resp = response) {
                    status.set(resp.code());
                    BufferedSource source = resp.body().source();
                    while (!source.exhausted()) {
                        String line = source.readUtf8Line();
                        if (line == null || line.isEmpty()) {
                            continue;
                        }
                        Map<String, Object> m = JSON.parseObject(line, Map.class);
                        if (m != null && m.containsKey("index")) {
                            indices.add(((Number) m.get("index")).intValue());
                        }
                    }
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "流式响应应在超时前结束");
        assertNull(failure.get(), "流式响应不得抛异常或 JSON 解析失败: " + failure.get());
        assertEquals(200, status.get());
        assertEquals(java.util.Arrays.asList(0, 1, 2), new java.util.ArrayList<>(indices),
                "应按顺序返回 index 0/1/2，实际: " + indices);
    }

    @Test
    void testTextStream() throws Exception {
        Request request = new Request.Builder().url(baseUrl() + "/text-stream").get().build();
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ConcurrentLinkedQueue<String> lines = new java.util.concurrent.ConcurrentLinkedQueue<>();
        AtomicInteger status = new AtomicInteger();
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
                    BufferedSource source = resp.body().source();
                    while (!source.exhausted()) {
                        String line = source.readUtf8Line();
                        if (line != null && !line.isEmpty()) {
                            lines.add(line);
                        }
                    }
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "文本流应在超时前结束");
        assertNull(failure.get(), "文本流不得抛异常: " + failure.get());
        assertEquals(200, status.get());
        assertEquals(java.util.Arrays.asList("line1", "line2"), new java.util.ArrayList<>(lines),
                "应按顺序返回 line1/line2，实际: " + lines);
    }
}
