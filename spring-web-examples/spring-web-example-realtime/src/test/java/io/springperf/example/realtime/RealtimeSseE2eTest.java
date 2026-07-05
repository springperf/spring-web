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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = RealtimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class RealtimeSseE2eTest {

    private TestRestTemplate rest;
    private OkHttpClient httpClient;
    private int actualPort;

    @Autowired
    private NettyHttpServer nettyHttpServer;

    @BeforeEach
    void setUp() {
        actualPort = nettyHttpServer.getActualPort();
        rest = new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://localhost:" + actualPort));
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Test
    void sse_subscribeAndSend_receivesEvent() throws Exception {
        String clientId = "sse-test-client-" + System.currentTimeMillis();

        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<String> receivedEvent = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();

        Request sseRequest = new Request.Builder()
                .url("http://localhost:" + actualPort + "/sse/subscribe?clientId=" + clientId)
                .build();

        EventSource.Factory factory = EventSources.createFactory(httpClient);
        EventSource eventSource = factory.newEventSource(sseRequest, new EventSourceListener() {
            @Override
            public void onEvent(EventSource eventSource, @Nullable String id, @Nullable String type, String data) {
                receivedEvent.set(data);
                eventLatch.countDown();
            }

            @Override
            public void onFailure(EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
                failure.set(t != null ? t.getMessage() : "unknown failure");
                eventLatch.countDown();
            }
        });

        // Give subscription time to register
        Thread.sleep(300);

        // Send event
        rest.getForEntity("/sse/send?clientId=" + clientId + "&data=hello-sse", String.class);

        // Verify SSE stream received the event
        boolean received = eventLatch.await(10, TimeUnit.SECONDS);
        eventSource.cancel();

        assertThat(failure.get()).as("SSE connection should not fail").isNull();
        assertThat(received).as("SSE client should receive event within timeout").isTrue();
        assertThat(receivedEvent.get()).isEqualTo("hello-sse");
    }

    @Test
    void sse_broadcast_sendsToAllClients() throws Exception {
        String clientId1 = "sse-bc-1-" + System.currentTimeMillis();
        String clientId2 = "sse-bc-2-" + System.currentTimeMillis();

        CountDownLatch connected1 = new CountDownLatch(1);
        CountDownLatch connected2 = new CountDownLatch(1);
        CountDownLatch event1 = new CountDownLatch(1);
        CountDownLatch event2 = new CountDownLatch(1);
        AtomicReference<String> data1 = new AtomicReference<>();
        AtomicReference<String> data2 = new AtomicReference<>();

        SubscribeResultHandler handler1 = new SubscribeResultHandler(connected1, event1, data1);
        SubscribeResultHandler handler2 = new SubscribeResultHandler(connected2, event2, data2);

        EventSource.Factory factory = EventSources.createFactory(httpClient);

        Request req1 = new Request.Builder()
                .url("http://localhost:" + actualPort + "/sse/subscribe?clientId=" + clientId1)
                .build();
        factory.newEventSource(req1, handler1);
        assertThat(connected1.await(5, TimeUnit.SECONDS))
                .as("Client 1 should connect within 5s")
                .isTrue();

        Request req2 = new Request.Builder()
                .url("http://localhost:" + actualPort + "/sse/subscribe?clientId=" + clientId2)
                .build();
        factory.newEventSource(req2, handler2);
        assertThat(connected2.await(5, TimeUnit.SECONDS))
                .as("Client 2 should connect within 5s")
                .isTrue();

        // Broadcast
        rest.getForEntity("/sse/broadcast?data=broadcast-msg", String.class);

        // Verify both clients received the broadcast
        boolean r1 = event1.await(10, TimeUnit.SECONDS);
        boolean r2 = event2.await(10, TimeUnit.SECONDS);

        assertThat(r1).as("Client 1 should receive broadcast").isTrue();
        assertThat(r2).as("Client 2 should receive broadcast").isTrue();
        assertThat(data1.get()).isEqualTo("broadcast-msg");
        assertThat(data2.get()).isEqualTo("broadcast-msg");
    }

    @Test
    void sse_sendToNonexistentClient_returnsClientNotFound() {
        String resp = rest.getForEntity(
                "/sse/send?clientId=nonexist-client&data=test", String.class).getBody();

        assertThat(resp).isEqualTo("client not found: nonexist-client");
    }

    private static class SubscribeResultHandler extends EventSourceListener {
        final CountDownLatch connected;
        final CountDownLatch eventReceived;
        final AtomicReference<String> data;

        SubscribeResultHandler(CountDownLatch connected, CountDownLatch eventReceived,
                               AtomicReference<String> data) {
            this.connected = connected;
            this.eventReceived = eventReceived;
            this.data = data;
        }

        @Override
        public void onOpen(EventSource eventSource, Response response) {
            connected.countDown();
        }

        @Override
        public void onEvent(EventSource eventSource, @Nullable String id, @Nullable String type, String data) {
            this.data.set(data);
            eventReceived.countDown();
        }

        @Override
        public void onFailure(EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
            eventReceived.countDown();
        }
    }
}