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

        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<String> receivedEvent = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();

        Request sseRequest = new Request.Builder()
                .url("http://localhost:" + actualPort + "/sse/subscribe?clientId=" + clientId)
                .build();

        EventSource.Factory factory = EventSources.createFactory(httpClient);
        EventSource eventSource = factory.newEventSource(sseRequest, new EventSourceListener() {
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                connectedLatch.countDown();
            }

            @Override
            public void onEvent(EventSource eventSource, @Nullable String id, @Nullable String type, String data) {
                receivedEvent.set(data);
                doneLatch.countDown();
            }

            @Override
            public void onFailure(EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
                failure.set(t != null ? t.getMessage() : "unknown failure");
                doneLatch.countDown();
            }
        });

        // Wait for SSE connection to be established (instead of fixed sleep)
        assertThat(connectedLatch.await(5, TimeUnit.SECONDS))
                .as("SSE connection should be established within 5s")
                .isTrue();

        // Send event
        rest.getForEntity("/sse/send?clientId=" + clientId + "&data=hello-sse", String.class);

        // Wait for either event or failure
        boolean done = doneLatch.await(10, TimeUnit.SECONDS);

        // Check failure BEFORE cancel — cancel may trigger onFailure in some OkHttp versions,
        // which would falsely set failure after the event was successfully received
        assertThat(failure.get()).as("SSE connection should not fail").isNull();

        eventSource.cancel();

        assertThat(done).as("SSE client should receive event within timeout").isTrue();
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
        AtomicReference<String> failure1 = new AtomicReference<>();
        AtomicReference<String> failure2 = new AtomicReference<>();

        EventSource.Factory factory = EventSources.createFactory(httpClient);

        Request req1 = new Request.Builder()
                .url("http://localhost:" + actualPort + "/sse/subscribe?clientId=" + clientId1)
                .build();
        EventSource es1 = factory.newEventSource(req1, new BroadcastResultHandler(connected1, event1, data1, failure1));
        assertThat(connected1.await(5, TimeUnit.SECONDS))
                .as("Client 1 should connect within 5s")
                .isTrue();

        Request req2 = new Request.Builder()
                .url("http://localhost:" + actualPort + "/sse/subscribe?clientId=" + clientId2)
                .build();
        EventSource es2 = factory.newEventSource(req2, new BroadcastResultHandler(connected2, event2, data2, failure2));
        assertThat(connected2.await(5, TimeUnit.SECONDS))
                .as("Client 2 should connect within 5s")
                .isTrue();

        // Broadcast
        rest.getForEntity("/sse/broadcast?data=broadcast-msg", String.class);

        // Verify both clients received the broadcast
        boolean r1 = event1.await(10, TimeUnit.SECONDS);
        boolean r2 = event2.await(10, TimeUnit.SECONDS);

        assertThat(failure1.get()).as("Client 1 SSE connection should not fail").isNull();
        assertThat(failure2.get()).as("Client 2 SSE connection should not fail").isNull();
        assertThat(r1).as("Client 1 should receive broadcast").isTrue();
        assertThat(r2).as("Client 2 should receive broadcast").isTrue();
        assertThat(data1.get()).isEqualTo("broadcast-msg");
        assertThat(data2.get()).isEqualTo("broadcast-msg");

        // Clean up to prevent emitter leakage between tests
        es1.cancel();
        es2.cancel();
    }

    @Test
    void sse_sendToNonexistentClient_returnsClientNotFound() {
        String resp = rest.getForEntity(
                "/sse/send?clientId=nonexist-client&data=test", String.class).getBody();

        assertThat(resp).isEqualTo("client not found: nonexist-client");
    }

    private static class BroadcastResultHandler extends EventSourceListener {
        final CountDownLatch connected;
        final CountDownLatch eventReceived;
        final AtomicReference<String> data;
        final AtomicReference<String> failure;

        BroadcastResultHandler(CountDownLatch connected, CountDownLatch eventReceived,
                               AtomicReference<String> data, AtomicReference<String> failure) {
            this.connected = connected;
            this.eventReceived = eventReceived;
            this.data = data;
            this.failure = failure;
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
            failure.set(t != null ? t.getMessage() : "unknown failure");
        }
    }
}