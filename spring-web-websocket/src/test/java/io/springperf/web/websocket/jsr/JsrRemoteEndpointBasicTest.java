package io.springperf.web.websocket.jsr;

import jakarta.websocket.EncodeException;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JsrRemoteEndpointBasicTest {

    private WebSocketSession springSession = mock(WebSocketSession.class);
    private JsrCodecRegistry codecRegistry = new JsrCodecRegistry(
            new JsrEndpointConfigAdapter(new JsrEndpointMetadata(CodecEndpoint.class)));
    private JsrWebSocketSession session = new JsrWebSocketSession(springSession, new JsrWebSocketContainer(),
            codecRegistry, java.util.Collections.emptyMap());
    private JsrRemoteEndpointBasic remote = new JsrRemoteEndpointBasic(session, codecRegistry);

    @jakarta.websocket.server.ServerEndpoint(value = "/e", encoders = { TextEncoder.class })
    static class CodecEndpoint {
    }

    public static class TextEncoder implements jakarta.websocket.Encoder.Text<String> {
        @Override
        public String encode(String o) {
            return "encoded:" + o;
        }

        @Override
        public void init(jakarta.websocket.EndpointConfig config) {
        }

        @Override
        public void destroy() {
        }
    }

    @Test
    void sendText_sendsTextMessage() throws IOException {
        remote.sendText("hello");
        verify(springSession).sendMessage(new TextMessage("hello"));
    }

    @Test
    void sendBinary_sendsBinaryMessage() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8));
        remote.sendBinary(buf);
        verify(springSession).sendMessage(new BinaryMessage(buf));
    }

    /**
     * {@code AbstractWebSocketMessage.equals} 只比较 payload、忽略 isLast，因此下面必须用 ArgumentCaptor 显式断言该标志，否则"丢弃 isLast"的实现也能让
     * verify 通过。
     */
    @Test
    void sendText_nonLastFragment_propagatesIsLastFalse() throws IOException {
        remote.sendText("part", false);

        org.mockito.ArgumentCaptor<WebSocketMessage<?>> captor = org.mockito.ArgumentCaptor
                .forClass(WebSocketMessage.class);
        verify(springSession).sendMessage(captor.capture());
        assertFalse(captor.getValue().isLast(), "last=false 的分片必须透传，否则客户端会把分片当完整消息");
    }

    @Test
    void sendText_lastFragment_propagatesIsLastTrue() throws IOException {
        remote.sendText("part", true);

        org.mockito.ArgumentCaptor<WebSocketMessage<?>> captor = org.mockito.ArgumentCaptor
                .forClass(WebSocketMessage.class);
        verify(springSession).sendMessage(captor.capture());
        assertTrue(captor.getValue().isLast());
    }

    @Test
    void sendBinary_nonLastFragment_propagatesIsLastFalse() throws IOException {
        remote.sendBinary(ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8)), false);

        org.mockito.ArgumentCaptor<WebSocketMessage<?>> captor = org.mockito.ArgumentCaptor
                .forClass(WebSocketMessage.class);
        verify(springSession).sendMessage(captor.capture());
        assertFalse(captor.getValue().isLast(), "二进制分片的 last 标志同样必须透传");
    }

    @Test
    void sendText_partialAndLast_sendsFullText() throws IOException {
        remote.sendText("part", true);
        verify(springSession).sendMessage(new TextMessage("part"));
    }

    @Test
    void sendBinary_partialAndLast_sendsFullBinary() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8));
        remote.sendBinary(buf, false);
        verify(springSession).sendMessage(new BinaryMessage(buf));
    }

    @Test
    void getSendStream_unsupported() {
        assertThrows(UnsupportedOperationException.class, remote::getSendStream);
        assertThrows(UnsupportedOperationException.class, remote::getSendWriter);
    }

    @Test
    void sendObject_textEncoder_sendsText() throws IOException, EncodeException {
        remote.sendObject("world");
        verify(springSession).sendMessage(new TextMessage("encoded:world"));
    }

    @Test
    void batching_noop() throws IOException {
        remote.setBatchingAllowed(true);
        assertFalse(remote.getBatchingAllowed());
        remote.flushBatch();
    }

    @Test
    void sendPing_sendsPingMessage() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap("p".getBytes(StandardCharsets.UTF_8));
        remote.sendPing(buf);
        verify(springSession).sendMessage(new PingMessage(buf));
    }

    @Test
    void sendPong_sendsPongMessage() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap("o".getBytes(StandardCharsets.UTF_8));
        remote.sendPong(buf);
        verify(springSession).sendMessage(new PongMessage(buf));
    }
}
