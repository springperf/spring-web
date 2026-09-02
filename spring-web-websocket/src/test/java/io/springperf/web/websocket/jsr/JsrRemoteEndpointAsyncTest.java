package io.springperf.web.websocket.jsr;

import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JsrRemoteEndpointAsyncTest {

    private WebSocketSession springSession = mock(WebSocketSession.class);
    private JsrCodecRegistry codecRegistry = new JsrCodecRegistry(
            new JsrEndpointConfigAdapter(new JsrEndpointMetadata(CodecEndpoint.class)));
    private JsrWebSocketSession session = new JsrWebSocketSession(springSession, new JsrWebSocketContainer(),
            codecRegistry, Collections.emptyMap());
    private JsrRemoteEndpointAsync remote = new JsrRemoteEndpointAsync(session, codecRegistry);

    @jakarta.websocket.server.ServerEndpoint(value = "/e", encoders = {TextEncoder.class})
    static class CodecEndpoint {}

    public static class TextEncoder implements jakarta.websocket.Encoder.Text<String> {
        @Override public String encode(String o) { return "encoded:" + o; }
        @Override public void init(jakarta.websocket.EndpointConfig config) {}
        @Override public void destroy() {}
    }

    @Test
    void sendTimeout_getSet() {
        assertEquals(-1L, remote.getSendTimeout());
        remote.setSendTimeout(3000);
        assertEquals(3000L, remote.getSendTimeout());
    }

    @Test
    void sendText_handler_success() throws IOException {
        SendHandler handler = mock(SendHandler.class);
        remote.sendText("hello", handler);
        verify(springSession).sendMessage(new TextMessage("hello"));
        verify(handler).onResult(any(SendResult.class));
    }

    @Test
    void sendText_handler_failure() throws IOException {
        doThrow(new IOException("boom")).when(springSession).sendMessage(any(TextMessage.class));
        SendHandler handler = mock(SendHandler.class);
        remote.sendText("hello", handler);
        verify(handler).onResult(argThat(result -> !result.isOK()));
    }

    @Test
    void sendText_future_success() throws Exception {
        Future<Void> future = remote.sendText("hello");
        assertTrue(future.isDone());
        future.get();
        verify(springSession).sendMessage(new TextMessage("hello"));
    }

    @Test
    void sendText_future_failure() throws IOException {
        doThrow(new IOException("boom")).when(springSession).sendMessage(any(TextMessage.class));
        Future<Void> future = remote.sendText("hello");
        assertThrows(java.util.concurrent.ExecutionException.class, future::get);
    }

    @Test
    void sendBinary_future_success() throws Exception {
        ByteBuffer buf = ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8));
        Future<Void> future = remote.sendBinary(buf);
        future.get();
        verify(springSession).sendMessage(new BinaryMessage(buf));
    }

    @Test
    void sendBinary_handler_failure() throws IOException {
        doThrow(new IOException("boom")).when(springSession).sendMessage(any(BinaryMessage.class));
        SendHandler handler = mock(SendHandler.class);
        remote.sendBinary(ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8)), handler);
        verify(handler).onResult(argThat(result -> !result.isOK()));
    }

    @Test
    void sendObject_future_success() throws Exception {
        Future<Void> future = remote.sendObject("world");
        future.get();
        verify(springSession).sendMessage(new TextMessage("encoded:world"));
    }

    @Test
    void sendObject_handler_success() throws IOException {
        SendHandler handler = mock(SendHandler.class);
        remote.sendObject("world", handler);
        verify(handler).onResult(any(SendResult.class));
    }

    @Test
    void batching_noop() throws IOException {
        remote.setBatchingAllowed(true);
        assertFalse(remote.getBatchingAllowed());
        remote.flushBatch();
    }

    @Test
    void sendPingAndPong_sendsMessages() throws IOException {
        ByteBuffer p = ByteBuffer.wrap("p".getBytes(StandardCharsets.UTF_8));
        ByteBuffer o = ByteBuffer.wrap("o".getBytes(StandardCharsets.UTF_8));
        remote.sendPing(p);
        remote.sendPong(o);
        verify(springSession).sendMessage(new PingMessage(p));
        verify(springSession).sendMessage(new PongMessage(o));
    }
}
