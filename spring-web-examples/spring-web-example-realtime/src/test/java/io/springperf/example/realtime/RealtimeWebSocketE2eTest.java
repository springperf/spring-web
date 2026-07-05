package io.springperf.example.realtime;

import io.springperf.web.server.NettyHttpServer;
import okhttp3.*;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = RealtimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class RealtimeWebSocketE2eTest {

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
    void websocket_echoMessage() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();

        Request wsRequest = new Request.Builder()
                .url("ws://localhost:" + actualPort + "/ws/chat")
                .build();

        httpClient.newWebSocket(wsRequest, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send("hello-websocket");
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
        assertThat(received).as("WebSocket should receive echo response within timeout").isTrue();
        assertThat(failure.get()).as("WebSocket connection should not fail").isNull();
        assertThat(receivedMessage.get()).isEqualTo("echo: hello-websocket");
    }
}