package io.springperf.web.websocket.jsr;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Decoder;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 补充 {@link JsrEndpointWebSocketHandler} 分支：二进制/Pong/POJO 消息解码、 byte[]/ByteBuffer/Encoder 返回值发送、@OnError 缺失/异常、无参构造缺失、
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
        public static final AtomicReference<byte[]> PONG_PAYLOAD = new AtomicReference<>();

        @OnMessage
        public void onPong(PongMessage pong) {
            PONG_HANDLED.incrementAndGet();
            ByteBuffer data = pong.getApplicationData();
            byte[] bytes = new byte[data.remaining()];
            data.duplicate().get(bytes);
            PONG_PAYLOAD.set(bytes);
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
    }

    public static class PojoTextEncoder implements Encoder.Text<PojoMessage> {
        @Override
        public String encode(PojoMessage m) {
            return "encoded:" + m.value;
        }
    }

    @ServerEndpoint(value = "/pojo", decoders = { PojoTextDecoder.class }, encoders = { PojoTextEncoder.class })
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
        return mockSession("ws://localhost/ws/jsr/room-1");
    }

    private WebSocketSession mockSession(String uri) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create(uri));
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @ServerEndpoint("/session-path/{room}")
    public static class SessionPathEndpoint {
        public static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @OnMessage
        public void onMessage(Session session, @PathParam("room") String room, String message) {
            SEEN.add(room + ":" + message + ":" + (session != null));
        }
    }

    @ServerEndpoint("/no-message-param")
    public static class NoMessageParamEndpoint {
        public static final AtomicInteger INVOCATIONS = new AtomicInteger();

        @OnMessage
        public void onMessage(Session session) {
            INVOCATIONS.incrementAndGet();
        }
    }

    @Test
    void messageArgs_templateReusedAcrossMessages_andMessageSlotCleared() throws Exception {
        SessionPathEndpoint.SEEN.clear();
        JsrEndpointWebSocketHandler handler = handler(SessionPathEndpoint.class);
        WebSocketSession session = mockSession("ws://localhost/session-path/room-7");
        handler.afterConnectionEstablished(session);

        Object[] template = handler.onMessageArgsOf(session);
        assertEquals(3, template.length);
        assertTrue(template[0] instanceof JsrWebSocketSession, "SESSION 槽位在连接建立时即固定");
        assertEquals("room-7", template[1], "@PathParam 槽位同样在连接建立时固定");

        handler.handleMessage(session, new TextMessage("a"));
        assertEquals(List.of("room-7:a:true"), SessionPathEndpoint.SEEN);
        assertNull(template[2], "派发结束后必须清空消息槽位，否则上一帧的 payload 会一直挂在连接状态上");

        handler.handleMessage(session, new TextMessage("b"));
        assertEquals(List.of("room-7:a:true", "room-7:b:true"), SessionPathEndpoint.SEEN);
        assertSame(template, handler.onMessageArgsOf(session), "实参数组必须在连接内复用，不逐消息重建");
    }

    @Test
    void handleMessage_withoutMessageParam_invokesWithConstantArgsOnly() throws Exception {
        NoMessageParamEndpoint.INVOCATIONS.set(0);
        JsrEndpointWebSocketHandler handler = handler(NoMessageParamEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("ignored"));

        assertEquals(1, NoMessageParamEndpoint.INVOCATIONS.get(), "无消息参数时也必须正常派发");
        Object[] template = handler.onMessageArgsOf(session);
        assertEquals(1, template.length);
        assertTrue(template[0] instanceof JsrWebSocketSession);
    }

    private JsrEndpointWebSocketHandler handler(Class<?> endpointClass) {
        return new JsrEndpointWebSocketHandler(new JsrEndpointMetadata(endpointClass), new JsrWebSocketContainer());
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
        assertDoesNotThrow(
                () -> handler(BinaryReturnEndpoint.class).handleTransportError(session, new RuntimeException("x")));
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

        assertEquals(1, ThrowingOnErrorEndpoint.ERROR_INVOCATIONS.get(), "@OnMessage 抛异常应翻译为 @OnError");
        assertNull(handler.onMessageArgsOf(session)[0], "异常路径同样要清空消息槽位");
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
        assertDoesNotThrow(
                () -> handler(BinaryReturnEndpoint.class).afterConnectionClosed(mockSession(), CloseStatus.NORMAL));
    }

    @Test
    void pongMessage_decodedAndDispatched() throws Exception {
        PongReturnEndpoint.PONG_HANDLED.set(0);
        JsrEndpointWebSocketHandler handler = handler(PongReturnEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session,
                new org.springframework.web.socket.PongMessage(ByteBuffer.wrap(new byte[] { 1, 2 })));

        assertEquals(1, PongReturnEndpoint.PONG_HANDLED.get());
        assertArrayEquals(new byte[] { 1, 2 }, PongReturnEndpoint.PONG_PAYLOAD.get(),
                "Pong 的应用数据必须原样交给 @OnMessage(PongMessage)");
    }

    @Test
    void binaryMessage_decodedAndReturnValue_sendsBinary() throws Exception {
        JsrEndpointWebSocketHandler handler = handler(BinaryReturnEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] { 5, 6, 7 })));

        verify(session).sendMessage(
                argThat(msg -> msg instanceof BinaryMessage && ((BinaryMessage) msg).getPayload().remaining() == 3));
    }

    @Test
    void byteBufferReturnValue_sendsBinary() throws Exception {
        JsrEndpointWebSocketHandler handler = handler(ByteBufferReturnEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        byte[] payload = new byte[] { 9, 8 };
        handler.handleMessage(session, new BinaryMessage(ByteBuffer.wrap(payload)));

        verify(session).sendMessage(argThat(msg -> msg instanceof BinaryMessage));
    }

    @Test
    void pojoMessage_decodedWithDecoder_andEncodedWithEncoder() throws Exception {
        JsrEndpointWebSocketHandler handler = handler(PojoEndpoint.class);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("hello"));

        verify(session).sendMessage(
                argThat(msg -> msg instanceof TextMessage && ((TextMessage) msg).getPayload().equals("encoded:hello")));
    }

    @Test
    void instantiateEndpoint_withoutDefaultConstructor_throwsIllegalState() {
        JsrEndpointWebSocketHandler handler = handler(NoDefaultCtorEndpoint.class);
        assertThrows(IllegalStateException.class, () -> handler.afterConnectionEstablished(mockSession()));
    }

    /* ==================== 动态 MessageHandler 的原生类型分派 ==================== */

    @ServerEndpoint("/throwing-open")
    public static class ThrowingOnOpenEndpoint {
        @OnOpen
        public void onOpen() {
            throw new IllegalStateException("open fail");
        }
    }

    @Test
    void onOpenFailure_cleansUpStateAndRegistration() {
        JsrWebSocketContainer container = new JsrWebSocketContainer();
        JsrEndpointWebSocketHandler handler = new JsrEndpointWebSocketHandler(
                new JsrEndpointMetadata(ThrowingOnOpenEndpoint.class), container);
        WebSocketSession session = mockSession();

        assertThrows(Exception.class, () -> handler.afterConnectionEstablished(session));

        assertTrue(session.getAttributes().isEmpty(), "@OnOpen 失败后连接状态不能留在 attributes 里");
        assertTrue(container.openSessionsOf("/throwing-open").isEmpty(), "失败的连接必须从在线会话表移除");
    }

    @Test
    void dynamicBinaryHandler_receivesBinaryPayload() throws Exception {
        JsrWebSocketContainer container = new JsrWebSocketContainer();
        JsrEndpointWebSocketHandler handler = new JsrEndpointWebSocketHandler(
                new JsrEndpointMetadata(NoOnMessageEndpoint.class), container);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        List<Object> received = new CopyOnWriteArrayList<>();
        JsrWebSocketSession jsrSession = (JsrWebSocketSession) container.openSessionsOf("/no-message").iterator()
                .next();
        jsrSession.addMessageHandler(new jakarta.websocket.MessageHandler.Whole<ByteBuffer>() {
            @Override
            public void onMessage(ByteBuffer message) {
                received.add(message);
            }
        });

        handler.handleMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] { 4, 5 })));

        assertEquals(1, received.size(), "动态注册的 binary handler 必须拿到消息");
        assertEquals(2, ((ByteBuffer) received.get(0)).remaining());
    }

    @Test
    void dynamicPongHandler_receivesPongPayload() throws Exception {
        JsrWebSocketContainer container = new JsrWebSocketContainer();
        JsrEndpointWebSocketHandler handler = new JsrEndpointWebSocketHandler(
                new JsrEndpointMetadata(NoOnMessageEndpoint.class), container);
        WebSocketSession session = mockSession();
        handler.afterConnectionEstablished(session);

        List<Object> received = new CopyOnWriteArrayList<>();
        JsrWebSocketSession jsrSession = (JsrWebSocketSession) container.openSessionsOf("/no-message").iterator()
                .next();
        jsrSession.addMessageHandler(new jakarta.websocket.MessageHandler.Whole<PongMessage>() {
            @Override
            public void onMessage(PongMessage message) {
                received.add(message);
            }
        });

        handler.handleMessage(session,
                new org.springframework.web.socket.PongMessage(ByteBuffer.wrap(new byte[] { 1 })));

        assertEquals(1, received.size(), "动态注册的 pong handler 必须拿到 Pong");
        assertEquals(1, ((PongMessage) received.get(0)).getApplicationData().remaining());
    }
}
