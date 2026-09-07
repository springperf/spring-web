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
 * 浼橀泤鍋滄満 drain E2E 娴嬭瘯銆?
 * <p>楠岃瘉 {@link NettyHttpServer#stop()} 鐨?drain 璇箟锛?
 * <ul>
 *   <li>宸茶繘鍏ュ鐞嗕腑鐨勮姹傚湪鍋滄満鍚庝粛琚畬鏁村啓鍑猴紙涓嶈鎵撴柇锛?/li>
 *   <li>鍋滄満鍚庢柊璇锋眰琚嫆缁濓紙503 Service Unavailable锛?/li>
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
        // 1. 鍙戣捣涓€涓鐞嗕腑浼氭寔缁?~1s 鐨勫紓姝ユ參璇锋眰锛?demo/async 鍐呴儴 sleep 1s锛?
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

        // 纭繚鎱㈣姹傚凡杩涘叆澶勭悊绠＄嚎锛坅sync 绾跨▼宸插紑濮嬫墽琛岋級
        Thread.sleep(300);
        assertFalse(asyncDone.await(50, TimeUnit.MILLISECONDS),
                "鎱㈣姹傚湪鍋滄満鍓嶅簲灏氭湭瀹屾垚锛堥獙璇佸畠纭疄澶勪簬澶勭悊涓級");

        // 2. 瑙﹀彂浼橀泤鍋滄満锛氫粎鍏抽棴 accept + 鎷掔粷鏂拌姹傦紝EventLoop 淇濈暀鐢ㄤ簬鍐欏畬 in-flight 鍝嶅簲
        nettyHttpServer.stop();
        assertFalse(nettyHttpServer.isRunning(), "stop() 鍚庢湇鍔″櫒涓嶅啀杩愯");

        // 3. in-flight 鎱㈣姹傚簲琚畬鏁村啓鍑猴紙drain锛?
        assertTrue(asyncDone.await(5, TimeUnit.SECONDS), "in-flight 璇锋眰搴斿湪鍋滄満鍚庝粛琚畬鏁村鐞?);
        Response slow = asyncResp.get();
        assertNotNull(slow);
        assertEquals(200, slow.code(), "in-flight 璇锋眰搴旇繑鍥?200锛屽疄闄?" + slow.code());
        String body = slow.body() != null ? slow.body().string() : "";
        assertFalse(body.isEmpty(), "in-flight 璇锋眰 body 搴斾负瀹屾暣杈撳嚭");

        // 4. 鍋滄満鍚庢柊璇锋眰琚嫆缁濓紙503 鎴栬繛鎺ヨ鎷掞級
        Request newReq = new Request.Builder().url(url("/api/core/bytes")).get().build();
        boolean rejected = false;
        try (Response resp = CLIENT.newCall(newReq).execute()) {
            rejected = resp.code() == 503;
        } catch (java.io.IOException e) {
            rejected = true; // 鍋滄満鍚庢柊杩炴帴琚嫆
        }
        assertTrue(rejected, "鍋滄満鍚庢柊璇锋眰搴旇繑鍥?503 鎴栬繛鎺ヨ鎷?);
    }
}