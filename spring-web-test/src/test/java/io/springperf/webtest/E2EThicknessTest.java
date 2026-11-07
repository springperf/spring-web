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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        // 并发请求同一路径：结果只收集不判定，由主线程精确断言，避免工作线程异常被吞
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch latch = new CountDownLatch(n);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    try (Response resp = get(url("/api/core/bytes"))) {
                        bodies.add(resp.body().string());
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS), "并发请求应在超时前完成");
            assertNull(unexpected.get(), "并发请求不得抛异常: " + unexpected.get());
            assertEquals(n, bodies.size(), "每个并发请求都应拿到自己的响应");
            for (String body : bodies) {
                assertEquals("Hello, Bytes!", body, "并发请求响应不得串扰");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentIsolation_deferredResultAndSyncDoNotInterfere() throws Exception {
        // 并发调用：一个慢异步（/async 1s）+ 若干同步小请求
        // 工作线程内不能用 JUnit 断言：AssertionError 是 Error 而非 Exception，
        // pool.submit 的 Future 若不 get 就会被静默吞掉，finally 仍 countDown，测试永远绿。
        // 因此只收集结果/异常，全部由主线程精确断言。
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        AtomicReference<String> asyncBody = new AtomicReference<>();
        ConcurrentLinkedQueue<String> syncBodies = new ConcurrentLinkedQueue<>();

        try {
            pool.submit(() -> { // 慢异步
                try (Response r = get(url("/api/demo/async"))) {
                    asyncBody.set(r.body().string());
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
            for (int i = 0; i < 3; i++) {
                pool.submit(() -> {
                    try (Response r = get(url("/api/core/bytes"))) {
                        syncBodies.add(r.body().string());
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertNull(unexpected.get(), "并发请求不得抛异常: " + unexpected.get());
            Map<String, Object> async = JSON.parseObject(asyncBody.get(), Map.class);
            assertTrue(async.get("now") instanceof Number, "慢异步应返回 {\"now\":<时间戳>}，实际: " + asyncBody.get());
            assertEquals(3, syncBodies.size(), "3 个同步请求都应拿到各自的响应");
            for (String body : syncBodies) {
                assertEquals("Hello, Bytes!", body, "并发请求响应不得串扰");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedRequest_doesNotCorruptConnection() throws Exception {
        // 先发一个触发异常路径的请求（参数类型不匹配），随后同一连接上的正常请求必须仍能正确解析。
        // Response 必须关闭，否则连接不会归还连接池，测出来的"复用"是假的。
        for (int i = 0; i < 3; i++) {
            try (Response errorResp = get(url("/api/p1/type-mismatch?id=abc"))) {
                assertEquals(400, errorResp.code(), "异常请求应返回确定的错误码，实际: " + errorResp.code());
            }
            try (Response okResp = get(url("/api/core/bytes"))) {
                assertEquals("Hello, Bytes!", okResp.body().string(), "异常请求后连接应仍可正确复用");
            }
        }
    }

    @Test
    void errorThenKeepAlive_sameSocketStillUsable() throws Exception {
        // 触发 500（bad-pool）后，同一连接后续请求正常返回
        try (Response errorResp = get(url("/api/core/pool/bad-pool"))) {
            assertEquals(500, errorResp.code(), "bad-pool 应返回 500，实际: " + errorResp.code());
        }
        try (Response okResp = get(url("/api/core/bytes"))) {
            assertEquals("Hello, Bytes!", okResp.body().string(), "500 后连接应保持可用");
        }
    }

    private Response get(String u) throws Exception {
        Request req = new Request.Builder().url(u).get().build();
        return CLIENT.newCall(req).execute();
    }
}