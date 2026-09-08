package io.springperf.web.websocket.jsr;

import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 补充 {@link JsrEndpointWebSocketHandler} 分支：二进制/Pong/POJO 消息解码、
 * byte[]/ByteBuffer/Encoder 返回值发送、@OnError 缺失/异常、无参构造缺失、
 * EndpointConfig/CloseReason 参数注入等。
 */
class JsrEndpointWebSocketHandlerBranchesTest {

    @ServerEndpoint("/binary")
    public static class BinaryReturnEndpoint {
        @OnMessage
        public byte[] echoBinary(byte[] data) {
            return data;
        }
    }

    @ServerEndpoint("/buffer")
    public static class ByteBufferReturnEndpoint {
        @OnMessage
        public ByteBuffer echoBuffer(ByteBuffer data) {
            return data;
        }
    }

    @ServerEndpoint("/no-message")
    public static class NoOnMessageEndpoint {
        @OnOpen
        public void onOpen(Session session) {
        }
    }

    @ServerEndpoint("/no-error")
    public static class NoOnErrorEndpoint {
        @OnMessage
        public String echo(String s) {
            return s;
        }
    }

    @ServerEndpoint("/throwing-error")
    public static class ThrowingOnErrorEndpoint {
        public static final AtomicInteger ERROR_INVOCATIONS = new AtomicInteger();

        @OnMessage
        public String echo(String s) {
            throw new RuntimeException("boom");
        }

        @OnError
        public void onError(Throwable t) {
            ERROR_INVOCATIONS.incrementAndGet();
            throw new IllegalStateException("error handler itself fails");
        }
    }

    @ServerEndpoint("/config-close")
    public static class ConfigCloseEndpoint {
        public static final AtomicReference<String> OPEN_CONFIG = new AtomicReference<>();

        @OnOpen
        public void onOpen(Session session, EndpointConfig config) {
            OPEN_CONFIG.set(config.toString());
        }

        @OnClose
        public void onClose(CloseReason reason) {
        }
    }

    @ServerEndpoint("/pong")
    public static class PongReturnEndpoint {
        public static final AtomicInteger PONG_HANDLED = new AtomicInteger();

        @OnMessage
        public void onPong(PongMessage pong) {
            PONG_HANDLED.incrementAndGet();
        }
    }

    public static final class PojoMessage {
        public String value;

        PojoMessage() {
        }
    }

    public static class PojoTextDecoder implements Decoder.Text<PojoMessage> {
        @Override
        public PojoMessage decode(String s) {
            PojoMessage m = new PojoMessage();
            m.value = s;
            return m;
        }

        @Override
        public boolean willDecode(String s) {
            return true;
        }

        @Override
        public void init(EndpointConfig config) {
        }

        @Override
        public void destroy() {
        }
    }

    public static class PojoTextEncoder implements Encoder.Text<PojoMessage> {
        @Override
        public String encode(PojoMessage m) {
            return "encoded:" + m.value;
        }

        @Override
        public void init(EndpointConfig config) {
        }

        @Override
        public void destroy() {
        }
    }

    @ServerEndpoint(value = "/pojo", decoders = {PojoTextDecoder.class}, encoders = {PojoTextEncoder.class})
    public static class PojoEndpoint {
        @OnMessage
        public PojoMessage echo(PojoMessage m) {
            return m;
        }
    }

    @ServerEndpoint(value = "/no-ctor")
    public static class NoDefaultCtorEndpoint {
        @SuppressWarnings("unused")
        public NoDefaultCtorEndpoint(String required) {
        }

        @OnOpen
        public void onOpen() {
        }
    }

    private WebSocketSession mockSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/jsr/room-1"));
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private JsrEndpointWebSocketHandler handler(Class<?> endpointClass) {
        return new JsrEndpointWebSocketHandler(
                new JsrEndpointMetadata(endpointClass), new JsrWebSocketContainer());
    }

    @Test
    void supportsPartialMessages_returnsFalse() {
        assertFalse(handler(BinaryReturnEndpoint.class).supportsPartialMessages());
    }

    @Test
    void handleMessage_noOnMessage_noAction() throws Exception {
        JsrEndpointWebSocketHandler handler = handler(NoOnMessageEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage("ignored"));
        verify(session, never()).sendMessage(any());
    }

    @Test
    void handleTransportError_noState_returnsGracefully() {
        WebSocketSession session = mockSession();
        assertDoesNotThrow(() -> handler(BinaryReturnEndpoint.class)
                .handleTransportError(session, new RuntimeException("x")));
    }

    @Test
    void handleTransportError_invokesOnError() throws Exception {
        TestEchoEndpoint.ON_ERROR_COUNT.set(0);
        JsrEndpointWebSocketHandler handler = handler(TestEchoEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        handler.handleTransportError(session, new RuntimeException("transport"));

        assertEquals(1, TestEchoEndpoint.ON_ERROR_COUNT.get());
    }

    @Test
    void handleTransportError_noOnError_logsAndReturns() throws Exception {
        JsrEndpointWebSocketHandler handler = handler(NoOnErrorEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);
        assertDoesNotThrow(() -> handler.handleTransportError(session, new RuntimeException("x")));
    }

    @Test
    void handleEndpointError_errorHandlerThrows_swallowed() throws Exception {
        ThrowingOnErrorEndpoint.ERROR_INVOCATIONS.set(0);
        JsrEndpointWebSocketHandler handler = handler(ThrowingOnErrorEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("hi"));

        assertEquals(1, ThrowingOnErrorEndpoint.ERROR_INVOCATIONS.get(),
                "@OnMessage 抛异常应翻译为 @OnError");
    }

    @Test
    void onOpen_withEndpointConfigParam_injected() throws Exception {
        ConfigCloseEndpoint.OPEN_CONFIG.set(null);
        JsrEndpointWebSocketHandler handler = handler(ConfigCloseEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);
        assertNotNull(ConfigCloseEndpoint.OPEN_CONFIG.get(), "EndpointConfig 应注入 @OnOpen 参数");
    }

    @Test
    void onClose_withCloseReasonParam_injected() throws Exception {
        JsrEndpointWebSocketHandler handler = handler(ConfigCloseEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);
        assertDoesNotThrow(() -> handler.afterConnectionClosed(session, CloseStatus.NORMAL));
    }

    @Test
    void afterConnectionClosed_noState_returnsGracefully() throws Exception {
        assertDoesNotThrow(() -> handler(BinaryReturnEndpoint.class)
                .afterConnectionClosed(mockSession(), CloseStatus.NORMAL));
    }

    @Test
    void pongMessage_decodedAndDispatched() throws Exception {
        PongReturnEndpoint.PONG_HANDLED.set(0);
        JsrEndpointWebSocketHandler handler = handler(PongReturnEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session,
                new org.springframework.web.socket.PongMessage(ByteBuffer.wrap(new byte[]{1, 2})));

        assertEquals(1, PongReturnEndpoint.PONG_HANDLED.get());
    }

    @Test
    void binaryMessage_decodedAndReturnValue_sendsBinary() throws Exception {
        JsrEndpointWebSocketHandler handler = handler(BinaryReturnEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[]{5, 6, 7})));

        verify(session).sendMessage(argThat(msg -> msg instanceof BinaryMessage
                && ((BinaryMessage) msg).getPayload().remaining() == 3));
    }

    @Test
    void byteBufferReturnValue_sendsBinary() throws Exception {
        JsrEndpointWebSocketHandler handler = handler(ByteBufferReturnEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        byte[] payload = new byte[]{9, 8};
        handler.handleMessage(session, new BinaryMessage(ByteBuffer.wrap(payload)));

        verify(session).sendMessage(argThat(msg -> msg instanceof BinaryMessage));
    }

    @Test
    void pojoMessage_decodedWithDecoder_andEncodedWithEncoder() throws Exception {
        JsrEndpointWebSocketHandler handler = handler(PojoEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("hello"));

        verify(session).sendMessage(argThat(msg -> msg instanceof TextMessage
                && ((TextMessage) msg).getPayload().equals("encoded:hello")));
    }

    @Test
    void instantiateEndpoint_withoutDefaultConstructor_throwsIllegalState() {
        JsrEndpointWebSocketHandler handler = handler(NoDefaultCtorEndpoint.class);
        assertThrows(IllegalStateException.class, () -> handler.afterConnectionEstablished(mockSession()));
    }
}
