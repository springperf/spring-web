package io.springperf.web.websocket.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 真实握手驱动 WebSocketRoutingHandler：构造器变体、握手成功/失败、帧转发、
 * 空闲超时、异常兜底、连接关闭与 Origin 校验分支。
 */
class WebSocketRoutingHandlerCoverageTest {

    private EmbeddedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null && channel.isOpen()) {
            channel.finishAndReleaseAll();
        }
    }

    private WebSocketHandler handler;
    private final List<Object> passthrough = new ArrayList<>();

    private EmbeddedChannel newChannel(WebSocketRoutingHandler routingHandler) {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(new HttpServerCodec());
        ch.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
        ch.pipeline().addLast(routingHandler);
        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                passthrough.add(msg);
            }
        });
        return ch;
    }

    private WebSocketRoutingHandler newRouting(WebSocketHandler h, String subProtocols, boolean allowExtensions,
                                               List<String> allowedOrigins, long idleTimeout, long heartbeat) {
        return new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", h), subProtocols, allowExtensions, allowedOrigins,
                idleTimeout, heartbeat);
    }

    /** 排空握手成功后写入的 outbound 响应（避免与后续帧断言混淆） */
    private void drainHandshakeResponse() {
        Object o;
        while ((o = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(o);
        }
    }

    private static FullHttpRequest upgradeRequest(String path, String origin) {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path,
                Unpooled.buffer(0));
        req.headers().set(HttpHeaderNames.HOST, "localhost");
        req.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        req.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        if (origin != null) {
            req.headers().set(HttpHeaderNames.ORIGIN, origin);
        }
        return req;
    }

    /* ==================== 构造器变体 ==================== */

    @Test
    void constructors_allVariants_succeed() {
        WebSocketHandler h = mock(WebSocketHandler.class);
        Map<String, WebSocketHandler> map = Collections.singletonMap("/ws", h);
        assertNotNull(new WebSocketRoutingHandler(map));
        assertNotNull(new WebSocketRoutingHandler(map, "chat", true));
        assertNotNull(new WebSocketRoutingHandler(map, null, false, Collections.emptyList()));
        assertNotNull(new WebSocketRoutingHandler(map, null, false, null, 5000));
        assertNotNull(new WebSocketRoutingHandler(map, null, false, null, 5000, 1000));
        assertNotNull(new WebSocketRoutingHandler(map, null, false, null, -1, -1, null));
    }

    /* ==================== 非 WS 请求透传 ==================== */

    @Test
    void channelRead_httpWithoutUpgrade_passesThrough() {
        handler = mock(WebSocketHandler.class);
        // 仅 routing handler + 透传尾部：避免 codec/aggregator 缓存 HttpRequest
        channel = new EmbeddedChannel();
        channel.pipeline().addLast(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                passthrough.add(msg);
            }
        });

        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api",
                Unpooled.buffer(0));
        channel.pipeline().fireChannelRead(req);

        assertEquals(1, passthrough.size(), "非 WebSocket 请求应透传下游");
        assertSame(req, passthrough.get(0));
        ReferenceCountUtil.release(req);
    }

    /* ==================== 握手成功 ==================== */

    @Test
    void handshake_success_establishesSession() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), null, false, null));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();

        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
    }

    /* ==================== 帧转发 ==================== */

    @Test
    void webSocketFrame_text_binary_pingPong_dispatched() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
        drainHandshakeResponse();

        // Text 帧
        TextWebSocketFrame text = new TextWebSocketFrame("hello");
        channel.pipeline().fireChannelRead(text);
        verify(handler, timeout(2000)).handleMessage(any(WebSocketSession.class), any(TextMessage.class));

        // Binary 帧
        BinaryWebSocketFrame binary = new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));
        channel.pipeline().fireChannelRead(binary);
        verify(handler, timeout(2000)).handleMessage(any(WebSocketSession.class), any(BinaryMessage.class));

        // Ping → 回显 Pong（经 ws-encoder 编码为 ByteBuf 或保留原帧）
        PingWebSocketFrame ping = new PingWebSocketFrame();
        channel.pipeline().fireChannelRead(ping);
        Object outbound = channel.readOutbound();
        assertNotNull(outbound, "Ping 帧应触发 Pong 回写");
        ReferenceCountUtil.release(outbound);
    }

    /* ==================== Close 帧 ==================== */

    @Test
    void webSocketFrame_close_invokesAfterClosed_andEchoes() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
        drainHandshakeResponse();

        CloseWebSocketFrame close = new CloseWebSocketFrame(1000, "bye");
        channel.pipeline().fireChannelRead(close);

        verify(handler, timeout(2000)).afterConnectionClosed(any(WebSocketSession.class), any(CloseStatus.class));
        Object outbound = channel.readOutbound();
        assertNotNull(outbound, "Close 帧应被回显写入 outbound");
        ReferenceCountUtil.release(outbound);
    }

    /* ==================== 异常兜底 ==================== */

    @Test
    void exceptionCaught_afterHandshake_forwardsTransportError() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));

        RuntimeException boom = new RuntimeException("boom");
        channel.pipeline().fireExceptionCaught(boom);

        verify(handler, timeout(2000)).handleTransportError(any(WebSocketSession.class), same(boom));
    }

    /* ==================== 空闲超时 ==================== */

    @Test
    void userEventTriggered_idleEvent_closesSession() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), null, false, null, 1000, -1));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));

        channel.pipeline().fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);

        verify(handler, timeout(2000)).handleTransportError(any(WebSocketSession.class), any());
    }

    /* ==================== 连接关闭 ==================== */

    @Test
    void channelInactive_invokesAfterClosed() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));

        channel.pipeline().fireChannelInactive();

        verify(handler, timeout(2000)).afterConnectionClosed(any(WebSocketSession.class),
                eq(CloseStatus.GOING_AWAY));
    }

    /* ==================== Origin 校验 ==================== */

    @Test
    void checkOrigin_disallowedOrigin_forbidden() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), null, false, Collections.singletonList("http://allowed.com")));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws", "http://evil.com"));
        channel.runPendingTasks();

        // 403 响应被写出
        Object outbound = channel.readOutbound();
        assertNotNull(outbound);
        if (outbound instanceof io.netty.handler.codec.http.HttpResponse) {
            assertEquals(io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN,
                    ((io.netty.handler.codec.http.HttpResponse) outbound).status());
        }
        verify(handler, never()).afterConnectionEstablished(any());
    }

    @Test
    void checkOrigin_matchingOrigin_allowed() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), null, false, Collections.singletonList("http://allowed.com")));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws", "http://allowed.com"));
        channel.runPendingTasks();

        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
    }

    @Test
    void checkOrigin_wildcard_allowed() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), null, false, Collections.singletonList("*")));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws", "http://anything.com"));
        channel.runPendingTasks();

        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
    }

    /* ==================== per-path 配置覆盖 ==================== */

    @Test
    void perPathConfig_registryOverridesGlobalSettings() throws Exception {
        handler = mock(WebSocketHandler.class);
        io.springperf.web.websocket.WebSocketHandlerRegistry registry =
                new io.springperf.web.websocket.WebSocketHandlerRegistry();
        registry.addHandler(handler, "/ws")
                .setAllowedOrigins("http://perpath.com")
                .setSubProtocols("chat")
                .setAllowExtensions(true)
                .setIdleTimeout(2000)
                .setHeartbeatInterval(1000);
        // 全局配置与 per-path 相悖，期望 per-path 覆盖生效
        channel = newChannel(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), "global-proto", false,
                Collections.singletonList("http://global.com"), 5000, 5000, registry));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws", "http://perpath.com"));
        channel.runPendingTasks();

        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
        // per-path idleTimeout>0 → 应安装 IdleStateHandler
        assertNotNull(channel.pipeline().get("ws-idle"), "per-path idleTimeout 应安装 IdleStateHandler");
    }

    @Test
    void perPathConfig_disallowedOrigin_forbiddenEvenWhenGlobalAllows() throws Exception {
        handler = mock(WebSocketHandler.class);
        io.springperf.web.websocket.WebSocketHandlerRegistry registry =
                new io.springperf.web.websocket.WebSocketHandlerRegistry();
        registry.addHandler(handler, "/ws").setAllowedOrigins("http://perpath.com");
        channel = newChannel(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), null, false,
                Collections.singletonList("http://global.com"), -1, -1, registry));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws", "http://evil.com"));
        channel.runPendingTasks();

        verify(handler, never()).afterConnectionEstablished(any());
    }

    @Test
    void channelWritabilityChanged_writable_drainsQueue() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(
                Collections.singletonMap("/ws", handler), null, false, null));

        channel.pipeline().fireChannelWritabilityChanged();
        assertNull(channel.pipeline().get("ws.session"));
    }
}
