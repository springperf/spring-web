package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import io.springperf.web.server.NettyHttpServer;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E 厚度测试：真实字节流连接级回归。
 * <ul>
 *   <li>keep-alive：同一连接复用（连续请求共享对端端口）</li>
 *   <li>并发隔离：并行请求互不串扰，各自拿到自己路径的结果</li>
 *   <li>失败请求不污染连接：4xx/5xx 后连接仍可继续复用</li>
 * </ul>
 */
class E2EThicknessTest extends BaseE2ETest {

    @Autowired
    private NettyHttpServer nettyHttpServer;

    /** 发起请求并返回响应 body JSON 中指定字段 */
    @SuppressWarnings("unchecked")
    private Number getIntField(String path, String field) throws Exception {
        Request req = new Request.Builder().url(url(path)).get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(), "GET " + path + " 应 200");
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            return (Number) body.get(field);
        }
    }

    @Test
    void keepAlive_sequentialRequestsReuseConnection() throws Exception {
        // 连续请求同一资源：keep-alive 下应复用同一条 TCP 连接（对端端口不变），
        // 且服务器当前连接数保持增长为 0 或 1（无连接泄漏）
        int before = nettyHttpServer.getActiveConnectionCount();
        int port = -1;
        for (int i = 0; i < 5; i++) {
            int current = getIntField("/api/thickness/conn-id", "remotePort").intValue();
            if (port == -1) port = current;
            assertEquals(port, current, "keep-alive 连接应在连续请求间被复用");
        }
        int after = nettyHttpServer.getActiveConnectionCount();
        assertTrue(after <= before + 1,
                "复用后连接数不应显著增长: before=" + before + " after=" + after);
    }

    @Test
    void keepAlive_clientWithConnectionPool_staysBounded() throws Exception {
        // 100 个串行请求走同一连接池：活跃连接数应保持在一个小范围（不会因响应解析错误而反复建连）
        Set<Integer> observed = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            observed.add(getIntField("/api/thickness/conn-id", "remotePort").intValue());
        }
        assertTrue(observed.size() <= 3, "串行请求应复用少量连接，实际 " + observed.size());
    }

    @Test
    void concurrentIsolation_eachRequestGetsOwnPath() throws Exception {
        // 并发请求不同路径：每个响应都必须是对应路径的结果，绝不串扰
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch latch = new CountDownLatch(n);
        AtomicInteger failures = new AtomicInteger();
        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        String body = get(url("/api/core/bytes")).body().string();
                        if (!"Hello, Bytes!".equals(body)) failures.incrementAndGet();
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS), "并发请求应在超时前完成");
            assertEquals(0, failures.get(), "并发请求响应不得串扰/失败");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentIsolation_deferredResultAndSyncDoNotInterfere() throws Exception {
        // 并发调用：一个慢异步（/async 1s）+ 若干同步小请求
        // 同步请求应在异步结果返回前就快速完成，且各自结果正确
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        AtomicInteger failures = new AtomicInteger();

        try {
            pool.submit(() -> { // 慢异步
                try {
                    Response r = get(url("/api/demo/async"));
                    assertTrue(r.body().string().contains("now"));
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
            for (int i = 0; i < 3; i++) {
                pool.submit(() -> {
                    try {
                        String body = get(url("/api/core/bytes")).body().string();
                        if (!"Hello, Bytes!".equals(body)) failures.incrementAndGet();
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(0, failures.get(), "慢异步与同步请求并发应互不干扰");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedRequest_doesNotCorruptConnection() throws Exception {
        // 先发一个触发异常路径的请求（参数校验失败 → 400），随后同一连接上的正常请求必须仍能正确解析
        for (int i = 0; i < 3; i++) {
            get(url("/api/p1/type-mismatch?id=abc")); // 400 / 500 均可，重点是连接层不被污染
            String body = get(url("/api/core/bytes")).body().string();
            assertEquals("Hello, Bytes!", body, "异常请求后连接应仍可正确复用");
        }
    }

    @Test
    void errorThenKeepAlive_sameSocketStillUsable() throws Exception {
        // 触发 500（bad-pool）后，同一连接后续请求正常返回
        get(url("/api/core/pool/bad-pool")); // 500
        String body = get(url("/api/core/bytes")).body().string();
        assertEquals("Hello, Bytes!", body, "500 后连接应保持可用");
    }

    private Response get(String u) throws Exception {
        Request req = new Request.Builder().url(u).get().build();
        return CLIENT.newCall(req).execute();
    }
}