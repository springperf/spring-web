package io.springperf.webtest;

import io.springperf.web.server.NettyHttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 优雅停机 drain E2E 测试。
 * <p>验证 {@link NettyHttpServer#stop()} 的 drain 语义：
 * <ul>
 *   <li>已进入处理中的请求在停机后仍被完整写出（不被打断）</li>
 *   <li>停机后新请求被拒绝（503 Service Unavailable）</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.web.e2e.graceful-shutdown=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
public class GracefulShutdownE2eTest {

    @LocalServerPort
    private int serverPort;

    @Autowired
    private NettyHttpServer nettyHttpServer;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }

    @Test
    void inFlightRequestCompletesAfterStop_andNewRequestsRejected() throws Exception {
        // 1. 发起一个处理中会持续 ~1s 的异步慢请求（/demo/async 内部 sleep 1s）
        CountDownLatch asyncDone = new CountDownLatch(1);
        AtomicReference<Response> asyncResp = new AtomicReference<>();
        Request slowReq = new Request.Builder().url(url("/api/demo/async")).get().build();
        CLIENT.newCall(slowReq).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                asyncDone.countDown();
            }

            @Override
            public void onResponse(okhttp3.Call call, Response response) {
                asyncResp.set(response);
                asyncDone.countDown();
            }
        });

        // 确保慢请求已进入处理管线（async 线程已开始执行）
        Thread.sleep(300);
        assertFalse(asyncDone.await(50, TimeUnit.MILLISECONDS),
                "慢请求在停机前应尚未完成（验证它确实处于处理中）");

        // 2. 触发优雅停机：仅关闭 accept + 拒绝新请求，EventLoop 保留用于写完 in-flight 响应
        nettyHttpServer.stop();
        assertFalse(nettyHttpServer.isRunning(), "stop() 后服务器不再运行");

        // 3. in-flight 慢请求应被完整写出（drain）
        assertTrue(asyncDone.await(5, TimeUnit.SECONDS), "in-flight 请求应在停机后仍被完整处理");
        Response slow = asyncResp.get();
        assertNotNull(slow);
        assertEquals(200, slow.code(), "in-flight 请求应返回 200，实际 " + slow.code());
        String body = slow.body() != null ? slow.body().string() : "";
        assertFalse(body.isEmpty(), "in-flight 请求 body 应为完整输出");

        // 4. 停机后新请求被拒绝（503 或连接被拒）
        Request newReq = new Request.Builder().url(url("/api/core/bytes")).get().build();
        boolean rejected = false;
        try (Response resp = CLIENT.newCall(newReq).execute()) {
            rejected = resp.code() == 503;
        } catch (java.io.IOException e) {
            rejected = true; // 停机后新连接被拒
        }
        assertTrue(rejected, "停机后新请求应返回 503 或连接被拒");
    }
}