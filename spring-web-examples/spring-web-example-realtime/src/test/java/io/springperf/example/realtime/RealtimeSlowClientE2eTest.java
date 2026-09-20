package io.springperf.example.realtime;

import io.springperf.web.server.NettyHttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 慢客户端（订阅后**不读**响应）下的 SSE 行为 E2E。
 *
 * <p>覆盖三件事：</p>
 * <ol>
 *   <li><b>生产者不被永久阻塞</b>：向停滞客户端推送远超 TCP 窗口的数据，{@code /sse/broadcast}
 *       必须在有界时间内返回（数据进入发送器有界队列/由背压承接，而不是把调用线程拖死）；</li>
 *   <li><b>慢客户端不拖累其他客户端与请求处理</b>：同一时刻另一客户端仍能及时收到事件，
 *       且服务端仍能正常响应其他请求（EventLoop 未被 drain 阻塞）；</li>
 *   <li><b>客户端异常断开后自动清理</b>：emitter 最终被移除，后续推送得到明确拒绝，
 *       不会留下「永远写不出去」的僵尸订阅。</li>
 * </ol>
 */
@SpringBootTest(
        classes = RealtimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class RealtimeSlowClientE2eTest {

    /** 单个事件负载：2KB（须明显小于 Netty 默认 maxInitialLineLength=4096，避免请求行超限）。 */
    private static final String PAYLOAD = "x".repeat(2048);
    /** 推送次数：1000 × 2KB ≈ 2MB，足以填满 TCP 窗口使 channel 变为不可写。 */
    private static final int BULK_EVENTS = 1000;

    private TestRestTemplate rest;
    private OkHttpClient httpClient;
    private int port;

    @Autowired
    private NettyHttpServer nettyHttpServer;

    @BeforeEach
    void setUp() {
        port = nettyHttpServer.getActualPort();
        rest = new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://localhost:" + port)
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10)));
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    @Test
    void slowClient_doesNotBlockProducer_orOtherClients_andIsCleanedUpOnDisconnect() throws Exception {
        String slowId = "slow-" + System.currentTimeMillis();
        String fastId = "fast-" + System.currentTimeMillis();

        // ---- 1. 慢客户端：裸 socket 发订阅请求后【从不读取响应】 ----
        Socket slowSocket = new Socket();
        slowSocket.connect(new InetSocketAddress("localhost", port), 5000);
        slowSocket.setSoTimeout(200);
        OutputStream out = slowSocket.getOutputStream();
        out.write(("GET /sse/subscribe?clientId=" + slowId + " HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        awaitSubscribed(slowId);

        // ---- 2. 正常客户端：作为「慢客户端不拖累他人」的对照 ----
        CountDownLatch fastConnected = new CountDownLatch(1);
        CountDownLatch fastMarker = new CountDownLatch(1);
        AtomicReference<String> fastFailure = new AtomicReference<>();
        EventSource fastSource = EventSources.createFactory(httpClient).newEventSource(
                new Request.Builder()
                        .url("http://localhost:" + port + "/sse/subscribe?clientId=" + fastId)
                        .build(),
                new EventSourceListener() {
                    @Override
                    public void onOpen(EventSource eventSource, Response response) {
                        fastConnected.countDown();
                    }

                    @Override
                    public void onEvent(EventSource eventSource, @Nullable String id,
                                        @Nullable String type, String data) {
                        if ("MARKER".equals(data)) {
                            fastMarker.countDown();
                        }
                    }

                    @Override
                    public void onFailure(EventSource eventSource, @Nullable Throwable t,
                                          @Nullable Response response) {
                        fastFailure.set(t != null ? t.getMessage() : "unknown");
                    }
                });
        assertThat(fastConnected.await(5, TimeUnit.SECONDS))
                .as("对照客户端应在 5s 内建立连接").isTrue();

        try {
            // ---- 3. 灌入远超 TCP 窗口的数据：broadcast 必须在有界时间内返回 ----
            long start = System.nanoTime();
            for (int i = 0; i < BULK_EVENTS; i++) {
                String body = rest.getForObject("/sse/broadcast?data=" + PAYLOAD, String.class);
                assertThat(parseSentCount(body))
                        .as("第 %s 次 broadcast 应至少送达慢客户端与对照客户端（实际=%s）", i, body)
                        .isGreaterThanOrEqualTo(2);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs)
                    .as("向停滞客户端推送 %s 个事件耗费 %s ms：生产者被阻塞或实现为同步等待", BULK_EVENTS, elapsedMs)
                    .isLessThan(30_000);

            // ---- 4. 慢客户端停滞期间，对照客户端仍须及时收到事件 ----
            assertThat(parseSentCount(rest.getForObject("/sse/broadcast?data=MARKER", String.class)))
                    .as("MARKER 广播应至少送达两个客户端")
                    .isGreaterThanOrEqualTo(2);
            assertThat(fastMarker.await(5, TimeUnit.SECONDS))
                    .as("慢客户端停滞时，其他客户端仍应按时收到事件").isTrue();
            assertThat(fastFailure.get()).as("对照客户端不应收到失败").isNull();

            // ---- 5. 服务端整体仍可响应（EventLoop 未被阻塞） ----
            String probe = rest.getForObject("/sse/send?clientId=nonexist&data=x", String.class);
            assertThat(probe).isEqualTo("client not found: nonexist");

            // ---- 6. 慢客户端异常断开（RST）→ emitter 最终必须被移除 ----
            slowSocket.setSoLinger(true, 0);
            slowSocket.close();

            String finalState = null;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                // broadcast 会捕获 IOException 并移除失败的客户端；用它驱动清理探测
                try {
                    rest.getForObject("/sse/broadcast?data=after-close", String.class);
                } catch (Exception ignored) {
                    // 单次失败不影响最终状态判定
                }
                try {
                    finalState = rest.getForObject("/sse/send?clientId=" + slowId + "&data=x", String.class);
                } catch (Exception e) {
                    finalState = "EXCEPTION:" + e.getClass().getSimpleName();
                }
                if (finalState != null && finalState.startsWith("client not found")) {
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(finalState)
                    .as("慢客户端断开后 emitter 必须被自动移除（否则是僵尸订阅泄漏），实际=%s", finalState)
                    .startsWith("client not found");

            // ---- 7. 对照客户端不受影响（慢客户端已被移除，不应再计入送达数） ----
            assertThat(parseSentCount(rest.getForObject("/sse/broadcast?data=after-close-2", String.class)))
                    .as("慢客户端已被移除后，对照客户端仍应收到广播")
                    .isGreaterThanOrEqualTo(1);
        } finally {
            slowSocket.close();
            fastSource.cancel();
        }
    }

    /**
     * 解析 {@code /sse/broadcast} 的送达数。用「至少 N」而非「恰好 N」断言：同一模块的其他测试
     * 类可能复用 Spring 上下文并遗留 emitter（map 是单例），精确计数会造成偶发失败。
     */
    private static int parseSentCount(String body) {
        assertThat(body).as("broadcast 响应格式（实际=%s）", body).startsWith("ok, sent to ");
        String rest = body.substring("ok, sent to ".length());
        return Integer.parseInt(rest.split(" ")[0]);
    }

    /** 轮询等待服务端完成 emitter 注册（subscribe 返回前 put 已同步完成）。 */
    private void awaitSubscribed(String clientId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try {
                ResponseEntity<String> resp =
                        rest.getForEntity("/sse/send?clientId=" + clientId + "&data=probe", String.class);
                if (resp.getBody() != null && resp.getBody().startsWith("ok")) {
                    return;
                }
            } catch (Exception ignored) {
                // 连接尚未建立时可能出现异常，继续轮询
            }
            Thread.sleep(50);
        }
        throw new AssertionError("慢客户端订阅未在 5s 内注册：" + clientId);
    }
}
