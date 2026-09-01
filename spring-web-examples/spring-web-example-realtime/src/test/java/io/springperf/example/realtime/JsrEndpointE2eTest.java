package io.springperf.example.realtime;

import io.springperf.web.server.NettyHttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSR-356 {@code @ServerEndpoint} 端点 E2E：验证注解端点经本框架 Netty WebSocket
 * 管线完成握手、路径变量注入与 {@code @OnMessage} 回显。
 */
@SpringBootTest(
        classes = RealtimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class JsrEndpointE2eTest {

    private OkHttpClient httpClient;
    private int actualPort;

    @Autowired
    private NettyHttpServer nettyHttpServer;

    @BeforeEach
    void setUp() {
        actualPort = nettyHttpServer.getActualPort();
        httpClient = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Test
    void jsrEndpoint_echoWithPathParam() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();

        Request wsRequest = new Request.Builder()
                .url("ws://localhost:" + actualPort + "/ws/jsr/room-100")
                .build();

        httpClient.newWebSocket(wsRequest, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send("hello-jsr");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                receivedMessage.set(text);
                latch.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                failure.set(t.getMessage());
                latch.countDown();
            }
        });

        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertThat(received).as("JSR-356 WebSocket should receive echo within timeout").isTrue();
        assertThat(failure.get()).as("JSR-356 WebSocket connection should not fail").isNull();
        assertThat(receivedMessage.get()).isEqualTo("echo:room-100:hello-jsr");
    }
}
