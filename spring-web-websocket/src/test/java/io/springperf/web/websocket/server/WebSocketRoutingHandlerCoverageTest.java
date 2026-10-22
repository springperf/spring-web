package io.springperf.web.websocket.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 真实握手驱动 WebSocketRoutingHandler：构造器变体、握手成功/失败、帧转发、 空闲超时、异常兜底、连接关闭与 Origin 校验分支。
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
        return new WebSocketRoutingHandler(Collections.singletonMap("/ws", h), subProtocols, allowExtensions,
                allowedOrigins, idleTimeout, heartbeat);
    }

    /** 排空握手成功后写入的 outbound 响应（避免与后续帧断言混淆） */
    private void drainHandshakeResponse() {
        Object o;
        while ((o = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(o);
        }
    }

    /** 取回握手时挂在 channel 上的 session，用于主动 close 等操作。 */
    private NettyWebSocketSession sessionOf(EmbeddedChannel ch) {
        NettyWebSocketSession session = WebSocketRoutingHandler.sessionOf(ch);
        assertNotNull(session, "握手成功后 channel 上应挂有 session");
        return session;
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
        channel.pipeline()
                .addLast(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));
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
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();

        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
    }

    /* ==================== 帧转发 ==================== */

    @Test
    void webSocketFrame_text_binary_pingPong_dispatched() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
        drainHandshakeResponse();

        // Text 帧
        TextWebSocketFrame text = new TextWebSocketFrame("hello");
        channel.pipeline().fireChannelRead(text);
        verify(handler, timeout(2000)).handleMessage(any(WebSocketSession.class), any(TextMessage.class));

        // Binary 帧
        BinaryWebSocketFrame binary = new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[] { 1, 2, 3 }));
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
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));
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

    /* ==================== size limit：单帧与分片必须一致 ==================== */

    @Test
    void singleTextFrameOverLimit_rejected() throws Exception {
        // decoder 的 maxFrameSize = max(textLimit, binaryLimit) = 64KB，20KB 文本单帧能过 Netty 解码，
        // 但 session 的文本限制是 8KB。修复前只有分片路径校验，单帧直接派发——
        // 于是同一条消息"切成两片被拒、一次发完被收"，单帧成了绕过 size limit 的通道。
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        drainHandshakeResponse();

        char[] big = new char[20 * 1024];
        java.util.Arrays.fill(big, 'a');
        channel.pipeline().fireChannelRead(new TextWebSocketFrame(new String(big)));
        channel.runPendingTasks();

        verify(handler, timeout(2000)).handleTransportError(any(WebSocketSession.class), any());
        verify(handler, never()).handleMessage(any(WebSocketSession.class), any());
        assertFalse(channel.isOpen(), "超限的单帧消息必须关闭连接，与分片路径一致");
    }

    @Test
    void singleTextFrameWithinLimit_dispatched() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        drainHandshakeResponse();

        char[] ok = new char[4 * 1024];
        java.util.Arrays.fill(ok, 'a');
        channel.pipeline().fireChannelRead(new TextWebSocketFrame(new String(ok)));

        verify(handler, timeout(2000)).handleMessage(any(WebSocketSession.class), any(TextMessage.class));
    }

    /* ==================== 异常兜底 ==================== */

    @Test
    void exceptionCaught_afterHandshake_forwardsTransportError() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));
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
        channel = newChannel(
                new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null, 1000, -1));
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
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));

        // 必须真正 close()：Netty 保证 channelInactive 触发时 channel 已 inactive，
        // 这正是修复前 isOpen() 守卫恒为 false、回调丢失的成因。
        // 旧写法用 fireChannelInactive() 而 channel 仍 active，断言会假通过。
        channel.close().sync();

        verify(handler, timeout(2000)).afterConnectionClosed(any(WebSocketSession.class), eq(CloseStatus.GOING_AWAY));
    }

    @Test
    void channelInactive_notifiesExactlyOnce() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
        drainHandshakeResponse();

        // 先发 Close 帧（该路径先赢得通知权），再真正关闭 TCP。
        // 两条路径竞争下 afterConnectionClosed 必须只触发一次，且用对端给的 status。
        channel.pipeline().fireChannelRead(new CloseWebSocketFrame(1000, "bye"));
        channel.close().sync();

        verify(handler, timeout(2000)).afterConnectionClosed(any(WebSocketSession.class),
                eq(new CloseStatus(1000, "bye")));
        verify(handler, times(1)).afterConnectionClosed(any(WebSocketSession.class), any(CloseStatus.class));
    }

    @Test
    void serverInitiatedClose_notifiesAfterClosed() throws Exception {
        // 服务端主动 close()：只写 Close 帧后关 channel，自己不通知，
        // 回调应由随后的 channelInactive 补上（修复前这条路径完全丢失）。
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
        drainHandshakeResponse();

        WebSocketSession session = sessionOf(channel);
        session.close(new CloseStatus(CloseStatus.NORMAL.getCode(), "server-going-away"));

        verify(handler, timeout(2000)).afterConnectionClosed(any(WebSocketSession.class), any(CloseStatus.class));
    }

    @Test
    void closeFrame_handlerThrows_stillEchoesClose() throws Exception {
        // 业务回调抛异常不能吞掉 RFC 6455 §7.4.1 要求回显的 Close 帧：
        // 否则对端一直等回执、连接僵死到超时。
        handler = mock(WebSocketHandler.class);
        doThrow(new RuntimeException("close hook boom")).when(handler)
                .afterConnectionClosed(any(WebSocketSession.class), any(CloseStatus.class));
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        drainHandshakeResponse();

        channel.pipeline().fireChannelRead(new CloseWebSocketFrame(1000, "bye"));

        Object outbound = channel.readOutbound();
        assertNotNull(outbound, "回调抛异常时仍必须回显 Close 帧");
        ReferenceCountUtil.release(outbound);
    }

    @Test
    void channelInactive_handlerThrows_stillCompletesTeardown() throws Exception {
        // 回调抛异常不能让 channelInactive 中断：中断后 fireChannelInactive 不再向下传播，
        // 后续 handler 的资源清理（引用计数、连接统计）会整体丢失。
        handler = mock(WebSocketHandler.class);
        doThrow(new RuntimeException("close hook boom")).when(handler)
                .afterConnectionClosed(any(WebSocketSession.class), any(CloseStatus.class));
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));
        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();
        drainHandshakeResponse();

        assertDoesNotThrow(() -> channel.close().sync(), "业务回调抛异常不得从 channelInactive 逃逸");

        verify(handler, timeout(2000)).afterConnectionClosed(any(WebSocketSession.class), any(CloseStatus.class));
        assertFalse(channel.isOpen(), "连接拆除应正常完成");
    }

    /* ==================== Origin 校验 ==================== */

    @Test
    void checkOrigin_disallowedOrigin_forbidden() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false,
                Collections.singletonList("http://allowed.com")));

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
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false,
                Collections.singletonList("http://allowed.com")));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws", "http://allowed.com"));
        channel.runPendingTasks();

        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
    }

    @Test
    void checkOrigin_wildcard_allowed() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false,
                Collections.singletonList("*")));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws", "http://anything.com"));
        channel.runPendingTasks();

        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
    }

    /* ==================== per-path 配置覆盖 ==================== */

    @Test
    void perPathConfig_registryOverridesGlobalSettings() throws Exception {
        handler = mock(WebSocketHandler.class);
        io.springperf.web.websocket.WebSocketHandlerRegistry registry = new io.springperf.web.websocket.WebSocketHandlerRegistry();
        registry.addHandler(handler, "/ws").setAllowedOrigins("http://perpath.com").setSubProtocols("chat")
                .setAllowExtensions(true).setIdleTimeout(2000).setHeartbeatInterval(1000);
        // 全局配置与 per-path 相悖，期望 per-path 覆盖生效
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), "global-proto",
                false, Collections.singletonList("http://global.com"), 5000, 5000, registry));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws", "http://perpath.com"));
        channel.runPendingTasks();

        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
        // per-path idleTimeout>0 → 应安装 IdleStateHandler
        assertNotNull(channel.pipeline().get("ws-idle"), "per-path idleTimeout 应安装 IdleStateHandler");
    }

    @Test
    void perPathConfig_disallowedOrigin_forbiddenEvenWhenGlobalAllows() throws Exception {
        handler = mock(WebSocketHandler.class);
        io.springperf.web.websocket.WebSocketHandlerRegistry registry = new io.springperf.web.websocket.WebSocketHandlerRegistry();
        registry.addHandler(handler, "/ws").setAllowedOrigins("http://perpath.com");
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false,
                Collections.singletonList("http://global.com"), -1, -1, registry));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws", "http://evil.com"));
        channel.runPendingTasks();

        verify(handler, never()).afterConnectionEstablished(any());
    }

    @Test
    void channelWritabilityChanged_writable_drainsQueue() throws Exception {
        handler = mock(WebSocketHandler.class);
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));

        channel.pipeline().fireChannelWritabilityChanged();
        assertNull(channel.pipeline().get("ws.session"));
    }

    /* ==================== L14：per-path 帧上限覆盖 ==================== */

    @Test
    void perPathConfig_messageSizeLimit_overridesSessionDefault() throws Exception {
        handler = mock(WebSocketHandler.class);
        io.springperf.web.websocket.WebSocketHandlerRegistry registry = new io.springperf.web.websocket.WebSocketHandlerRegistry();
        registry.addHandler(handler, "/ws").setMessageSizeLimit(1024 * 1024);
        channel = newChannel(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null,
                -1, -1, registry));

        channel.pipeline().fireChannelRead(upgradeRequest("/ws", null));
        channel.runPendingTasks();

        ArgumentCaptor<WebSocketSession> captor = ArgumentCaptor.forClass(WebSocketSession.class);
        verify(handler, timeout(2000)).afterConnectionEstablished(captor.capture());
        WebSocketSession session = captor.getValue();
        assertInstanceOf(NettyWebSocketSession.class, session);
        assertEquals(1024 * 1024, ((NettyWebSocketSession) session).getTextMessageSizeLimit(),
                "per-path messageSizeLimit 应覆盖 session 默认 8KB text 上限");
        assertEquals(1024 * 1024, ((NettyWebSocketSession) session).getBinaryMessageSizeLimit(),
                "per-path messageSizeLimit 应覆盖 session 默认 64KB binary 上限");
    }
}
