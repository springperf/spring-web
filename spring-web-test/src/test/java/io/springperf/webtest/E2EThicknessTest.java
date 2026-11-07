package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import io.springperf.web.server.NettyHttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
    private Number getIntField(String path, String field) throws Exception {
        return getIntField(CLIENT, path, field);
    }

    /** 指定客户端版本（连接身份断言要用独立连接池，见 {@link #freshClient()}） */
    @SuppressWarnings("unchecked")
    private Number getIntField(OkHttpClient client, String path, String field) throws Exception {
        Request req = new Request.Builder().url(url(path)).get().build();
        try (Response resp = client.newCall(req).execute()) {
            assertEquals(200, resp.code(), "GET " + path + " 应 200");
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            return (Number) body.get(field);
        }
    }

    /** 独立连接池客户端：共享 CLIENT 里可能有余留空闲连接，会污染"同一连接"类的判定。 */
    private static OkHttpClient freshClient() {
        return new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3)).readTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(10)).build();
    }

    private static void closeQuietly(OkHttpClient client) {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }

    /** 取当前连接的对端端口（连接身份） */
    private int remotePort(OkHttpClient client) throws Exception {
        return getIntField(client, "/api/thickness/conn-id", "remotePort").intValue();
    }

    @Test
    void keepAlive_sequentialRequestsReuseConnection() throws Exception {
        // 连续请求同一资源：keep-alive 下应复用同一条 TCP 连接（对端端口不变）；
        // 活动连接数最多因首请求新建一条（before 已含其它测试类留在共享连接池里的空闲连接）
        int before = nettyHttpServer.getActiveConnectionCount();
        int port = -1;
        for (int i = 0; i < 5; i++) {
            int current = getIntField("/api/thickness/conn-id", "remotePort").intValue();
            if (port == -1) port = current;
            assertEquals(port, current, "keep-alive 连接应在连续请求间被复用");
        }
        int after = nettyHttpServer.getActiveConnectionCount();
        assertTrue(after <= before + 1, "复用后连接数不应增长超过 1 条: before=" + before + " after=" + after);
    }

    @Test
    void keepAlive_sequentialRequestsUseSingleConnection() throws Exception {
        // 50 个串行请求走同一连接池：只应出现 1 条连接（server.max-keep-alive-requests 默认 0=不限）
        Set<Integer> observed = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            observed.add(getIntField("/api/thickness/conn-id", "remotePort").intValue());
        }
        assertEquals(1, observed.size(), "串行请求应复用同一条连接，实际出现 " + observed.size() + " 条: " + observed);
    }

    @Test
    void concurrentIsolation_eachRequestGetsOwnPath() throws Exception {
        // 每个请求打不同路径（path-var 回显 token），才能发现响应串包；
        // 工作线程内只收集不判定（AssertionError 是 Error，submit 的 Future 不 get 会被静默吞掉）
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch latch = new CountDownLatch(n);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Map<String, String> received = new ConcurrentHashMap<>();
        try {
            for (int i = 0; i < n; i++) {
                String token = "tok-" + i;
                pool.submit(() -> {
                    try (Response resp = get(url("/api/edge/path-var/" + token))) {
                        received.put(token, resp.body().string());
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS), "并发请求应在超时前完成");
            assertNull(unexpected.get(), "并发请求不得抛异常: " + unexpected.get());
            assertEquals(n, received.size(), "每个并发请求都应拿到自己的响应");
            received.forEach((token, body) -> assertEquals(token, JSON.parseObject(body).getString("id"),
                    "并发请求响应串扰：请求 " + token + " 收到 " + body));
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
        Map<String, String> syncBodies = new ConcurrentHashMap<>();

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
                String token = "sync-" + i;
                pool.submit(() -> {
                    try (Response r = get(url("/api/edge/path-var/" + token))) {
                        syncBodies.put(token, r.body().string());
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS), "并发请求应在超时前完成");
            assertNull(unexpected.get(), "并发请求不得抛异常: " + unexpected.get());
            Map<String, Object> async = JSON.parseObject(asyncBody.get(), Map.class);
            assertTrue(async.get("now") instanceof Number, "慢异步应返回 {\"now\":<时间戳>}，实际: " + asyncBody.get());
            assertEquals(3, syncBodies.size(), "3 个同步请求都应拿到各自的响应");
            syncBodies.forEach((token, body) -> assertEquals(token, JSON.parseObject(body).getString("id"),
                    "慢异步不得串到同步请求的响应：请求 " + token + " 收到 " + body));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedRequest_doesNotCorruptConnection() throws Exception {
        // 先发一个触发异常路径的请求（参数类型不匹配），随后同一连接上的正常请求必须仍能正确解析。
        // 必须显式核对 remotePort：只断言 body 时，服务端关连接 + OkHttp 重连也能通过；
        // 用独立连接池排除共享 CLIENT 里余留空闲连接的干扰。
        OkHttpClient client = freshClient();
        try {
            int port = remotePort(client);
            for (int i = 0; i < 3; i++) {
                try (Response errorResp = client
                        .newCall(new Request.Builder().url(url("/api/p1/type-mismatch?id=abc")).get().build())
                        .execute()) {
                    assertEquals(400, errorResp.code(), "异常请求应返回确定的错误码，实际: " + errorResp.code());
                }
                try (Response okResp = client.newCall(new Request.Builder().url(url("/api/core/bytes")).get().build())
                        .execute()) {
                    assertEquals("Hello, Bytes!", okResp.body().string(), "异常请求后连接应仍可正确复用");
                }
                assertEquals(port, remotePort(client), "4xx 后应复用同一条连接（远程端口不变）");
            }
        } finally {
            closeQuietly(client);
        }
    }

    @Test
    void errorThenKeepAlive_sameSocketStillUsable() throws Exception {
        // 触发 500（bad-pool）后，同一连接后续请求正常返回（用 remotePort 证明还是同一条 socket）
        OkHttpClient client = freshClient();
        try {
            int port = remotePort(client);
            try (Response errorResp = client
                    .newCall(new Request.Builder().url(url("/api/core/pool/bad-pool")).get().build()).execute()) {
                assertEquals(500, errorResp.code(), "bad-pool 应返回 500，实际: " + errorResp.code());
            }
            try (Response okResp = client.newCall(new Request.Builder().url(url("/api/core/bytes")).get().build())
                    .execute()) {
                assertEquals("Hello, Bytes!", okResp.body().string(), "500 后连接应保持可用");
            }
            assertEquals(port, remotePort(client), "500 后应仍是同一条连接（远程端口不变）");
        } finally {
            closeQuietly(client);
        }
    }

    private Response get(String u) throws Exception {
        Request req = new Request.Builder().url(u).get().build();
        return CLIENT.newCall(req).execute();
    }
}