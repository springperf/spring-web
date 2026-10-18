package io.springperf.web.websocket.jsr;

import io.springperf.web.websocket.server.NettyWebSocketSession;
import jakarta.websocket.CloseReason;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JsrWebSocketSessionTest {

    private WebSocketSession springSession;
    private JsrWebSocketContainer container;
    private JsrCodecRegistry codecRegistry;
    private JsrWebSocketSession session;

    @BeforeEach
    void setUp() {
        springSession = mock(WebSocketSession.class);
        container = new JsrWebSocketContainer();
        codecRegistry = new JsrCodecRegistry(
                new JsrEndpointConfigAdapter(new JsrEndpointMetadata(PlainEndpoint.class)));
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("roomId", "42");
        session = new JsrWebSocketSession(springSession, container, codecRegistry, pathParams);
    }

    @jakarta.websocket.server.ServerEndpoint("/ws")
    static class PlainEndpoint {
    }

    @Test
    void getContainer_returnsContainer() {
        assertSame(container, session.getContainer());
    }

    @Test
    void getMessageHandlers_empty() {
        assertTrue(session.getMessageHandlers().isEmpty());
    }

    @Test
    void removeMessageHandler_noop() {
        session.removeMessageHandler(mock(MessageHandler.class));
    }

    /* ==================== 动态 MessageHandler 注册 ==================== */

    @Test
    void addMessageHandler_anonymousWhole_infersTypeFromGenerics() {
        when(springSession.isOpen()).thenReturn(true);
        MessageHandler handler = new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String message) {
            }
        };

        session.addMessageHandler(handler);

        assertTrue(session.getMessageHandlers().contains(handler));
    }

    /** 规范：无 Class 的重载在泛型被擦除（lambda）时必须失败，提示改用带 Class 的重载。 */
    @Test
    void addMessageHandler_lambdaWithoutClass_throwsIllegalState() {
        when(springSession.isOpen()).thenReturn(true);
        MessageHandler handler = (MessageHandler.Whole<String>) message -> {
        };

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> session.addMessageHandler(handler));
        assertTrue(ex.getMessage().contains("addMessageHandler(Class"), ex.getMessage());
    }

    /** 规范：每种原生消息类型最多一个 handler，重复注册抛 IllegalStateException。 */
    @Test
    void addMessageHandler_duplicateNativeType_throwsIllegalState() {
        when(springSession.isOpen()).thenReturn(true);
        session.addMessageHandler(String.class, message -> {
        });

        assertThrows(IllegalStateException.class, () -> session.addMessageHandler(String.class, message -> {
        }));
    }

    @Test
    void addMessageHandler_textAndBinaryCoexist() {
        when(springSession.isOpen()).thenReturn(true);
        session.addMessageHandler(String.class, message -> {
        });
        session.addMessageHandler(byte[].class, message -> {
        });

        assertEquals(2, session.getMessageHandlers().size());
    }

    /** 注解 {@code @OnMessage} 占用某原生类型后，同类型的动态注册必须被拒绝。 */
    @Test
    void addMessageHandler_annotatedTypeOccupied_throwsIllegalState() {
        when(springSession.isOpen()).thenReturn(true);
        session.markAnnotatedMessageType(String.class);

        assertThrows(IllegalStateException.class, () -> session.addMessageHandler(String.class, message -> {
        }));
    }

    /** 注解占用 text 不影响 binary 的动态注册。 */
    @Test
    void addMessageHandler_annotatedTextStillAllowsBinary() {
        when(springSession.isOpen()).thenReturn(true);
        session.markAnnotatedMessageType(String.class);

        session.addMessageHandler(byte[].class, message -> {
        });

        assertEquals(1, session.getMessageHandlers().size());
    }

    @Test
    void addMessageHandler_afterClose_throwsIllegalState() {
        when(springSession.isOpen()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> session.addMessageHandler(String.class, message -> {
        }));
    }

    @Test
    void addMessageHandler_unresolvableType_throwsIllegalArgument() {
        when(springSession.isOpen()).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> session.addMessageHandler(Runnable.class, message -> {
        }));
    }

    @Test
    void removeMessageHandler_removesRegisteredHandler() {
        when(springSession.isOpen()).thenReturn(true);
        MessageHandler.Whole<String> handler = message -> {
        };
        session.addMessageHandler(String.class, handler);

        session.removeMessageHandler(handler);

        assertTrue(session.getMessageHandlers().isEmpty());
    }

    @Test
    void getMessageHandlers_isUnmodifiable() {
        when(springSession.isOpen()).thenReturn(true);
        session.addMessageHandler(String.class, message -> {
        });

        assertThrows(UnsupportedOperationException.class, () -> session.getMessageHandlers().clear());
    }

    @Test
    void getProtocolVersion_13() {
        assertEquals("13", session.getProtocolVersion());
    }

    @Test
    void getNegotiatedSubprotocol_delegates() {
        when(springSession.getAcceptedProtocol()).thenReturn("chat");
        assertEquals("chat", session.getNegotiatedSubprotocol());
    }

    @Test
    void getNegotiatedExtensions_empty() {
        assertTrue(session.getNegotiatedExtensions().isEmpty());
    }

    /** 握手协商成功时（如 permessage-deflate）必须如实上报，否则业务误判「压缩未生效」。 */
    @Test
    void getNegotiatedExtensions_mapsNegotiatedExtension() {
        when(springSession.getExtensions()).thenReturn(java.util.List
                .of(new WebSocketExtension("permessage-deflate", Map.of("server_no_context_takeover", ""))));

        java.util.List<jakarta.websocket.Extension> extensions = session.getNegotiatedExtensions();

        assertEquals(1, extensions.size());
        jakarta.websocket.Extension extension = extensions.get(0);
        assertEquals("permessage-deflate", extension.getName());
        assertEquals(1, extension.getParameters().size());
        assertEquals("server_no_context_takeover", extension.getParameters().get(0).getName());
        assertEquals("", extension.getParameters().get(0).getValue());
    }

    @Test
    void isSecure_checksScheme() {
        when(springSession.getUri()).thenReturn(URI.create("wss://host/ws"));
        assertTrue(session.isSecure());
        when(springSession.getUri()).thenReturn(URI.create("ws://host/ws"));
        assertFalse(session.isSecure());
    }

    @Test
    void isSecure_nullUri_returnsFalse() {
        // 某些容器/测试桩的 getUri() 为 null：不能 NPE，按非安全处理
        assertFalse(session.isSecure());
    }

    @Test
    void setMaxIdleTimeout_propagatesToNettySession() {
        // 修复前只赋值字段、pipeline 的 IdleStateHandler 不变，导致 JSR 的
        // Session.setMaxIdleTimeout 完全不生效。这里断言真的委托到底层会话。
        NettyWebSocketSession netty = mock(NettyWebSocketSession.class);
        JsrWebSocketSession jsr = new JsrWebSocketSession(netty, container, codecRegistry, Collections.emptyMap());

        jsr.setMaxIdleTimeout(30000);

        assertEquals(30000L, jsr.getMaxIdleTimeout());
        verify(netty).setMaxIdleTimeout(30000L);
    }

    @Test
    void setMaxIdleTimeout_nonNettySession_onlyStoresValue() {
        // 非 Netty 实现（自定义 WebSocketSession）不得被强转：只存值即可
        assertDoesNotThrow(() -> session.setMaxIdleTimeout(1234));
        assertEquals(1234L, session.getMaxIdleTimeout());
    }

    @Test
    void isOpen_delegates() {
        when(springSession.isOpen()).thenReturn(true);
        assertTrue(session.isOpen());
    }

    @Test
    void maxIdleTimeout_getSet() {
        assertEquals(-1L, session.getMaxIdleTimeout());
        session.setMaxIdleTimeout(5000);
        assertEquals(5000L, session.getMaxIdleTimeout());
    }

    @Test
    void bufferSizes_delegate() {
        session.setMaxBinaryMessageBufferSize(100);
        verify(springSession).setBinaryMessageSizeLimit(100);
        session.setMaxTextMessageBufferSize(200);
        verify(springSession).setTextMessageSizeLimit(200);
        when(springSession.getBinaryMessageSizeLimit()).thenReturn(100);
        when(springSession.getTextMessageSizeLimit()).thenReturn(200);
        assertEquals(100, session.getMaxBinaryMessageBufferSize());
        assertEquals(200, session.getMaxTextMessageBufferSize());
    }

    @Test
    void remoteEndpoints_available() {
        assertNotNull(session.getAsyncRemote());
        assertNotNull(session.getBasicRemote());
        assertTrue(session.getAsyncRemote() instanceof RemoteEndpoint.Async);
        assertTrue(session.getBasicRemote() instanceof RemoteEndpoint.Basic);
    }

    @Test
    void getId_delegates() {
        when(springSession.getId()).thenReturn("s1");
        assertEquals("s1", session.getId());
    }

    @Test
    void close_noArg_delegates() throws IOException {
        session.close();
        verify(springSession).close();
    }

    @Test
    void close_withReason_delegatesWithStatus() throws IOException {
        session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "bye"));
        verify(springSession).close(new CloseStatus(1000, "bye"));
    }

    @Test
    void getRequestURI_delegates() {
        URI uri = URI.create("ws://h/ws");
        when(springSession.getUri()).thenReturn(uri);
        assertSame(uri, session.getRequestURI());
    }

    @Test
    void getQueryString_fromUri() {
        when(springSession.getUri()).thenReturn(URI.create("ws://h/ws?token=abc&x=1"));
        assertEquals("token=abc&x=1", session.getQueryString());
    }

    @Test
    void getRequestParameterMap_parsesQuery() {
        when(springSession.getUri()).thenReturn(URI.create("ws://h/ws?token=abc&x=1&x=2"));
        Map<String, java.util.List<String>> params = session.getRequestParameterMap();
        assertEquals(2, params.size());
        assertEquals(Collections.singletonList("abc"), params.get("token"));
        assertEquals(java.util.List.of("1", "2"), params.get("x"));
    }

    @Test
    void getRequestParameterMap_noQuery_empty() {
        when(springSession.getUri()).thenReturn(URI.create("ws://h/ws"));
        assertTrue(session.getRequestParameterMap().isEmpty());
    }

    @Test
    void getRequestParameterMap_emptyQuery_empty() {
        // "ws://h/ws?" 的 rawQuery 是空串而非 null：两条早退分支都要走到
        when(springSession.getUri()).thenReturn(URI.create("ws://h/ws?"));
        assertTrue(session.getRequestParameterMap().isEmpty());
    }

    @Test
    void getRequestParameterMap_keyWithoutEquals_emptyValue() {
        // "?flag"：没有 '=' 的参数名同样要出现，值为空串（jakarta.websocket 契约）
        when(springSession.getUri()).thenReturn(URI.create("ws://h/ws?flag&x=1"));

        Map<String, java.util.List<String>> params = session.getRequestParameterMap();

        assertEquals(Collections.singletonList(""), params.get("flag"));
        assertEquals(Collections.singletonList("1"), params.get("x"));
    }

    /** 规范：参数值按 {@code application/x-www-form-urlencoded} 解码（%XX 与 '+'）。 */
    @Test
    void getRequestParameterMap_decodesEncodedValues() {
        when(springSession.getUri()).thenReturn(URI.create("ws://h/ws?name=hello%20world&plus=a+b"));

        Map<String, java.util.List<String>> params = session.getRequestParameterMap();

        assertEquals(Collections.singletonList("hello world"), params.get("name"));
        assertEquals(Collections.singletonList("a b"), params.get("plus"), "'+' 按 x-www-form-urlencoded 解为空格");
    }

    /** 非法百分号编码不能抛给业务：一个坏参数不该让整条会话的参数读取失败。 */
    @Test
    void decodeParameter_malformedEscape_returnsRawValue() {
        assertEquals("%ZZ", JsrWebSocketSession.decodeParameter("%ZZ"));
        assertEquals("%2", JsrWebSocketSession.decodeParameter("%2"));
        assertEquals("hello world", JsrWebSocketSession.decodeParameter("hello%20world"));
    }

    /**
     * POJO 同时配了 text 与 binary Decoder 时，两种帧都会交给 {@code @OnMessage}，所以两类都必须占位。
     */
    @Test
    void markAnnotatedMessageType_pojoWithBothDecoders_occupiesTextAndBinary() {
        when(springSession.isOpen()).thenReturn(true);
        JsrWebSocketSession dual = new JsrWebSocketSession(springSession, new JsrWebSocketContainer(),
                new JsrCodecRegistry(new JsrEndpointConfigAdapter(new JsrEndpointMetadata(DualDecoderEndpoint.class))),
                Collections.emptyMap());

        dual.markAnnotatedMessageType(DualMessage.class);

        assertThrows(IllegalStateException.class, () -> dual.addMessageHandler(String.class, message -> {
        }));
        assertThrows(IllegalStateException.class, () -> dual.addMessageHandler(byte[].class, message -> {
        }));
    }

    @jakarta.websocket.server.ServerEndpoint(value = "/dual", decoders = { DualTextDecoder.class,
            DualBinaryDecoder.class })
    static class DualDecoderEndpoint {
    }

    static class DualMessage {
    }

    public static class DualTextDecoder implements jakarta.websocket.Decoder.Text<DualMessage> {

        @Override
        public DualMessage decode(String s) {
            return new DualMessage();
        }

        @Override
        public boolean willDecode(String s) {
            return true;
        }

        @Override
        public void init(jakarta.websocket.EndpointConfig config) {
        }

        @Override
        public void destroy() {
        }
    }

    public static class DualBinaryDecoder implements jakarta.websocket.Decoder.Binary<DualMessage> {

        @Override
        public DualMessage decode(java.nio.ByteBuffer bytes) {
            return new DualMessage();
        }

        @Override
        public boolean willDecode(java.nio.ByteBuffer bytes) {
            return true;
        }

        @Override
        public void init(jakarta.websocket.EndpointConfig config) {
        }

        @Override
        public void destroy() {
        }
    }

    @Test
    void getQueryString_nullUri_returnsNull() {
        assertNull(session.getQueryString());
        assertTrue(session.getRequestParameterMap().isEmpty());
    }

    @Test
    void getCodecRegistry_returnsInjected() {
        assertSame(codecRegistry, session.getCodecRegistry());
    }

    @Test
    void getPathParameters_returnsInjected() {
        assertEquals("42", session.getPathParameters().get("roomId"));
    }

    @Test
    void getUserProperties_mutable() {
        session.getUserProperties().put("k", "v");
        assertEquals("v", session.getUserProperties().get("k"));
    }

    @Test
    void getUserPrincipal_delegates() {
        Principal principal = mock(Principal.class);
        when(springSession.getPrincipal()).thenReturn(principal);
        assertSame(principal, session.getUserPrincipal());
    }

    @Test
    void getOpenSessions_empty() {
        assertTrue(session.getOpenSessions().isEmpty());
    }

    /* ==================== 注册类型判定的剩余分支 ==================== */

    @Test
    void addMessageHandler_classPartial_registersPartialHandler() {
        when(springSession.isOpen()).thenReturn(true);
        MessageHandler.Partial<String> partial = (part, last) -> {
        };

        session.addMessageHandler(String.class, partial);

        assertTrue(session.getMessageHandlers().contains(partial));
    }

    /** 规范：Partial 只允许 text(String) 与 binary(byte[]/ByteBuffer)，其他类型必须被拒绝。 */
    @Test
    void addMessageHandler_partialWithPojoType_rejected() {
        when(springSession.isOpen()).thenReturn(true);
        MessageHandler.Partial<Object> partial = (part, last) -> {
        };

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> session.addMessageHandler(Object.class, partial));
        assertTrue(ex.getMessage().contains("partial handlers support"), ex.getMessage());
    }

    @Test
    void addMessageHandler_readerTypeOccupiesText() {
        when(springSession.isOpen()).thenReturn(true);
        session.addMessageHandler(java.io.Reader.class, (MessageHandler.Whole<java.io.Reader>) reader -> {
        });

        // Reader 走文本帧，占的是 text 类型
        assertThrows(IllegalStateException.class, () -> session.addMessageHandler(String.class, message -> {
        }));
    }

    @Test
    void addMessageHandler_inputStreamTypeOccupiesBinary() {
        when(springSession.isOpen()).thenReturn(true);
        session.addMessageHandler(java.io.InputStream.class, (MessageHandler.Whole<java.io.InputStream>) stream -> {
        });

        assertThrows(IllegalStateException.class, () -> session.addMessageHandler(byte[].class, bytes -> {
        }));
    }

    @Test
    void addMessageHandler_pojoWithTextDecoderOccupiesText() {
        when(springSession.isOpen()).thenReturn(true);
        JsrCodecRegistry registry = new JsrCodecRegistry(
                new JsrEndpointConfigAdapter(new JsrEndpointMetadata(TextDecoderEndpoint.class)));
        JsrWebSocketSession decodable = new JsrWebSocketSession(springSession, container, registry, Map.of());

        decodable.addMessageHandler(Pojo.class, (MessageHandler.Whole<Pojo>) pojo -> {
        });

        assertThrows(IllegalStateException.class, () -> decodable.addMessageHandler(String.class, message -> {
        }));
    }

    @Test
    void addMessageHandler_pojoWithBinaryDecoderOccupiesBinary() {
        when(springSession.isOpen()).thenReturn(true);
        JsrCodecRegistry registry = new JsrCodecRegistry(
                new JsrEndpointConfigAdapter(new JsrEndpointMetadata(BinaryDecoderEndpoint.class)));
        JsrWebSocketSession decodable = new JsrWebSocketSession(springSession, container, registry, Map.of());

        decodable.addMessageHandler(BinaryPojo.class, (MessageHandler.Whole<BinaryPojo>) pojo -> {
        });

        assertThrows(IllegalStateException.class, () -> decodable.addMessageHandler(byte[].class, bytes -> {
        }));
    }

    static final class Pojo {
    }

    static final class BinaryPojo {
    }

    @jakarta.websocket.server.ServerEndpoint(value = "/text-decoder", decoders = PojoTextDecoder.class)
    static class TextDecoderEndpoint {
    }

    public static class PojoTextDecoder implements jakarta.websocket.Decoder.Text<Pojo> {

        @Override
        public Pojo decode(String s) {
            return new Pojo();
        }

        @Override
        public boolean willDecode(String s) {
            return true;
        }
    }

    @jakarta.websocket.server.ServerEndpoint(value = "/binary-decoder", decoders = PojoBinaryDecoder.class)
    static class BinaryDecoderEndpoint {
    }

    public static class PojoBinaryDecoder implements jakarta.websocket.Decoder.Binary<BinaryPojo> {

        @Override
        public BinaryPojo decode(java.nio.ByteBuffer bytes) {
            return new BinaryPojo();
        }

        @Override
        public boolean willDecode(java.nio.ByteBuffer bytes) {
            return true;
        }
    }

    /* ==================== getOpenSessions 的同 endpoint 语义 ==================== */

    @Test
    void getOpenSessions_visibleToOtherSessionsOfSameEndpoint() {
        JsrWebSocketContainer shared = new JsrWebSocketContainer();
        JsrWebSocketSession first = new JsrWebSocketSession(springSession, shared, codecRegistry, Map.of(), "/room");
        JsrWebSocketSession second = new JsrWebSocketSession(springSession, shared, codecRegistry, Map.of(), "/room");
        JsrWebSocketSession other = new JsrWebSocketSession(springSession, shared, codecRegistry, Map.of(), "/other");
        shared.registerSession("/room", first);
        shared.registerSession("/room", second);
        shared.registerSession("/other", other);

        assertEquals(2, first.getOpenSessions().size(), "同一 endpoint 的会话必须互相可见");
        assertTrue(first.getOpenSessions().contains(second));
        assertEquals(1, other.getOpenSessions().size(), "不同 endpoint 的会话不能混进来");
    }

    /* ==================== 批处理发送路径 ==================== */

    @Test
    void getSpringSession_returnsDelegate() {
        assertSame(springSession, session.getSpringSession());
    }

    @Test
    void send_withNettySessionAndNoFlush_usesBatchedPath() throws IOException {
        NettyWebSocketSession netty = mock(NettyWebSocketSession.class);
        JsrWebSocketSession jsr = new JsrWebSocketSession(netty, container, codecRegistry, Map.of());

        jsr.send(new org.springframework.web.socket.TextMessage("batched"), false);

        // flush=false 时必须走 Netty 的暂存发送，等 flushBatch 统一刷出
        verify(netty).sendMessage(any(org.springframework.web.socket.WebSocketMessage.class), eq(false));
        verify(netty, never()).sendMessage(any(org.springframework.web.socket.WebSocketMessage.class));
    }

    @Test
    void send_withFlush_goesStraightToTransport() throws IOException {
        NettyWebSocketSession netty = mock(NettyWebSocketSession.class);
        JsrWebSocketSession jsr = new JsrWebSocketSession(netty, container, codecRegistry, Map.of());

        jsr.send(new org.springframework.web.socket.TextMessage("now"), true);

        verify(netty).sendMessage(any(org.springframework.web.socket.WebSocketMessage.class));
        verify(netty, never()).sendMessage(any(org.springframework.web.socket.WebSocketMessage.class), eq(false));
    }

    /* ==================== 查询参数解析的空对 ==================== */

    @Test
    void getRequestParameterMap_emptyPairSkipped() {
        when(springSession.getUri()).thenReturn(URI.create("ws://localhost/ws?a=1&&b=2"));

        Map<String, java.util.List<String>> params = session.getRequestParameterMap();

        assertEquals(java.util.List.of("1"), params.get("a"));
        assertEquals(java.util.List.of("2"), params.get("b"));
        assertEquals(2, params.size(), "空对这一项应被跳过，而不是产生一个空键");
    }
}
