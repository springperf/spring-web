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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * JSR-356 {@code @ServerEndpoint} 桥接全链路测试。
 *
 * <p>握手阶段复用生产 {@link WebSocketRoutingHandler} 走真实 Netty pipeline
 * （HttpServerCodec → HttpObjectAggregator → RoutingHandler），验证
 * {@code @ServerEndpoint} 端点能经现有握手管线完成升级并触发 {@code @OnOpen}；
 * 帧处理阶段通过直接驱动 {@link JsrEndpointWebSocketHandler} 验证
 * {@code @OnMessage} echo 与 {@code @OnClose} 翻译。</p>
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
        channel.pipeline().addLast(new WebSocketRoutingHandler(
                Map.of("/ws/jsr/{roomId}", handler), null, false, null));
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
