package io.springperf.web.websocket.jsr;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.springperf.web.websocket.server.WebSocketRoutingHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * JSR-356 {@code @ServerEndpoint} 桥接全链路测试。
 * <p>
 * 握手阶段复用生产 {@link WebSocketRoutingHandler} 走真实 Netty pipeline （HttpServerCodec → HttpObjectAggregator →
 * RoutingHandler），验证 {@code @ServerEndpoint} 端点能经现有握手管线完成升级并触发 {@code @OnOpen}； 帧处理阶段通过直接驱动
 * {@link JsrEndpointWebSocketHandler} 验证 {@code @OnMessage} echo 与 {@code @OnClose} 翻译。
 * </p>
 */
class JsrEndpointWebSocketHandlerIntegrationTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        TestEchoEndpoint.MESSAGES.clear();
        TestEchoEndpoint.OPEN_ROOM_IDS.clear();
        TestEchoEndpoint.CLOSE_ROOM_ID.set(null);
        TestEchoEndpoint.ON_ERROR_COUNT.set(0);

        JsrEndpointMetadata metadata = new JsrEndpointMetadata(TestEchoEndpoint.class);
        JsrEndpointWebSocketHandler handler = new JsrEndpointWebSocketHandler(metadata, new JsrWebSocketContainer());

        channel = new EmbeddedChannel();
        channel.pipeline().addLast(new HttpServerCodec());
        channel.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
        channel.pipeline().addLast(new WebSocketRoutingHandler(Map.of("/ws/jsr/{roomId}", handler), null, false, null));
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void handshake_viaProductionPipeline_invokesOnOpenWithPathParam() {
        FullHttpRequest req = upgradeRequest("/ws/jsr/room-42");
        channel.pipeline().fireChannelRead(req);
        channel.runPendingTasks();
        channel.runPendingTasks();

        assertEquals(java.util.Arrays.asList("room-42"), TestEchoEndpoint.OPEN_ROOM_IDS);
        assertNull(channel.pipeline().get(HttpObjectAggregator.class));
        assertNull(channel.pipeline().get(HttpServerCodec.class));
    }

    @Test
    void messageTranslation_invokesOnMessageAndEcho() throws Exception {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(TestEchoEndpoint.class);
        JsrEndpointWebSocketHandler handler = new JsrEndpointWebSocketHandler(metadata, new JsrWebSocketContainer());

        WebSocketSession springSession = mock(WebSocketSession.class);
        org.mockito.Mockito.when(springSession.getUri())
                .thenReturn(java.net.URI.create("ws://localhost/ws/jsr/room-7"));
        org.mockito.Mockito.when(springSession.getAttributes())
                .thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        org.mockito.Mockito.when(springSession.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(springSession);
        handler.handleMessage(springSession, new TextMessage("hello"));

        assertEquals(java.util.Arrays.asList("room-7:hello"), TestEchoEndpoint.MESSAGES);
        verify(springSession).sendMessage(any(TextMessage.class));
    }

    @Test
    void closeTranslation_invokesOnClose() throws Exception {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(TestEchoEndpoint.class);
        JsrEndpointWebSocketHandler handler = new JsrEndpointWebSocketHandler(metadata, new JsrWebSocketContainer());

        WebSocketSession springSession = mock(WebSocketSession.class);
        org.mockito.Mockito.when(springSession.getUri())
                .thenReturn(java.net.URI.create("ws://localhost/ws/jsr/room-9"));
        org.mockito.Mockito.when(springSession.getAttributes())
                .thenReturn(new java.util.concurrent.ConcurrentHashMap<>());

        handler.afterConnectionEstablished(springSession);
        handler.afterConnectionClosed(springSession, CloseStatus.NORMAL);

        assertEquals("room-9", TestEchoEndpoint.CLOSE_ROOM_ID.get());
    }

    /**
     * {@code Configurator#checkOrigin} 必须真正接进握手：判定失败时不得升级（不登记会话、连接被关闭）。
     */
    @Test
    void handshake_configuratorRejectsOrigin_forbidden() {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(OriginGuardedEndpoint.class);
        JsrWebSocketContainer container = new JsrWebSocketContainer();
        JsrEndpointWebSocketHandler guarded = new JsrEndpointWebSocketHandler(metadata, container);

        channel = new EmbeddedChannel();
        channel.pipeline().addLast(new HttpServerCodec());
        channel.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
        channel.pipeline().addLast(new WebSocketRoutingHandler(Map.of("/ws/jsr/origin", guarded), null, false, null));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws/jsr/origin"));
        channel.runPendingTasks();

        assertNotNull(channel.readOutbound(), "拒绝升级必须写回响应");
        assertTrue(container.openSessionsOf(metadata.getPath()).isEmpty(), "被拒绝的连接不得登记为打开会话");
        assertFalse(channel.isOpen(), "拒绝后应关闭连接");
    }

    @jakarta.websocket.server.ServerEndpoint(value = "/ws/jsr/origin", configurator = RejectingConfigurator.class)
    public static class OriginGuardedEndpoint {
    }

    public static class RejectingConfigurator extends jakarta.websocket.server.ServerEndpointConfig.Configurator {
        @Override
        public boolean checkOrigin(String originHeaderValue) {
            return false;
        }
    }

    /**
     * 动态注册的真实用法：端点在 {@code @OnOpen} 里给会话加 handler，之后到达的文本帧由它消费。
     */
    @Test
    void dynamicMessageHandler_registeredInOnOpen_receivesTextFrames() throws Exception {
        DynamicEndpoint.RECEIVED.clear();
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(DynamicEndpoint.class);
        JsrEndpointWebSocketHandler handler = new JsrEndpointWebSocketHandler(metadata, new JsrWebSocketContainer());
        WebSocketSession springSession = mockSession("ws://localhost/ws/jsr/dynamic");

        handler.afterConnectionEstablished(springSession);
        handler.handleMessage(springSession, new TextMessage("first"));
        handler.handleMessage(springSession, new TextMessage("second"));

        assertEquals(java.util.Arrays.asList("first", "second"), DynamicEndpoint.RECEIVED);
    }

    /** 规范：每种原生消息类型最多一个 handler；{@code @OnMessage} 已占用 text 时动态注册必须被拒。 */
    @Test
    void dynamicMessageHandler_forTypeOwnedByOnMessage_isRejected() throws Exception {
        ConflictingEndpoint.ERROR.set(null);
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(ConflictingEndpoint.class);
        JsrEndpointWebSocketHandler handler = new JsrEndpointWebSocketHandler(metadata, new JsrWebSocketContainer());

        handler.afterConnectionEstablished(mockSession("ws://localhost/ws/jsr/conflict"));

        assertTrue(ConflictingEndpoint.ERROR.get() instanceof IllegalStateException,
                "应抛 IllegalStateException，实际：" + ConflictingEndpoint.ERROR.get());
    }

    private static WebSocketSession mockSession(String uri) {
        WebSocketSession springSession = mock(WebSocketSession.class);
        org.mockito.Mockito.when(springSession.getUri()).thenReturn(java.net.URI.create(uri));
        org.mockito.Mockito.when(springSession.getAttributes())
                .thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        org.mockito.Mockito.when(springSession.isOpen()).thenReturn(true);
        return springSession;
    }

    /** 只有动态 handler 的端点。 */
    @jakarta.websocket.server.ServerEndpoint("/ws/jsr/dynamic")
    public static class DynamicEndpoint {

        static final java.util.List<String> RECEIVED = new java.util.concurrent.CopyOnWriteArrayList<>();

        @jakarta.websocket.OnOpen
        public void onOpen(jakarta.websocket.Session session) {
            session.addMessageHandler(String.class, (jakarta.websocket.MessageHandler.Whole<String>) RECEIVED::add);
        }
    }

    /** {@code @OnMessage} 已占用 text 的端点：再注册 text handler 必须失败。 */
    @jakarta.websocket.server.ServerEndpoint("/ws/jsr/conflict")
    public static class ConflictingEndpoint {

        static final java.util.concurrent.atomic.AtomicReference<Throwable> ERROR = new java.util.concurrent.atomic.AtomicReference<>();

        @jakarta.websocket.OnOpen
        public void onOpen(jakarta.websocket.Session session) {
            try {
                session.addMessageHandler(String.class, (jakarta.websocket.MessageHandler.Whole<String>) message -> {
                });
            } catch (RuntimeException ex) {
                ERROR.set(ex);
            }
        }

        @jakarta.websocket.OnMessage
        public void onMessage(String message) {
        }
    }

    @Test
    void unknownPath_doesNotIntercept() {
        FullHttpRequest req = upgradeRequest("/not-registered");
        channel.pipeline().fireChannelRead(req);
        channel.runPendingTasks();

        assertTrue(TestEchoEndpoint.OPEN_ROOM_IDS.isEmpty());
    }

    private static FullHttpRequest upgradeRequest(String path) {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path,
                Unpooled.buffer(0));
        req.headers().set(HttpHeaderNames.HOST, "localhost");
        req.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        req.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        return req;
    }
}
