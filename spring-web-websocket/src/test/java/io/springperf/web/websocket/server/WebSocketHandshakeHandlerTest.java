package io.springperf.web.websocket.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WebSocketHandshakeHandler} 的握手失败与旁路分支：版本不支持、建连回调抛异常、无 Host 头、TLS 证书、心跳。
 * <p>
 * 这些分支都不出现在「客户端正常发起 ws 握手」的主链路上，因此单独用 EmbeddedChannel 精确构造。 pipeline 里有 {@code HttpServerCodec}，故 outbound
 * 看到的是编码后的字节：HTTP 响应按文本断言，控制帧按首字节 （0x88 Close / 0x89 Ping）断言。
 * </p>
 */
class WebSocketHandshakeHandlerTest {

    private EmbeddedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null && channel.isOpen()) {
            channel.finishAndReleaseAll();
        }
    }

    private void newPipeline(WebSocketRoutingHandler routingHandler) {
        channel = new EmbeddedChannel();
        channel.pipeline().addLast(new HttpServerCodec());
        channel.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
        channel.pipeline().addLast(routingHandler);
    }

    private static FullHttpRequest upgradeRequest(String version, boolean withHost) {
        return upgradeRequest(version, withHost, "/ws");
    }

    private static FullHttpRequest upgradeRequest(String version, boolean withHost, String path) {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path,
                Unpooled.buffer(0));
        if (withHost) {
            req.headers().set(HttpHeaderNames.HOST, "localhost");
        }
        req.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        req.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, version);
        return req;
    }

    /**
     * 读空 outbound 并拼成文本（HTTP 响应经 codec 编码后是 ByteBuf，可能被拆成多段写）。
     */
    private String readOutboundText() {
        StringBuilder text = new StringBuilder();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            ByteBuf buf = assertInstanceOf(ByteBuf.class, outbound, "握手响应经 HttpServerCodec 编码后应是字节");
            try {
                text.append(buf.toString(StandardCharsets.UTF_8));
            } finally {
                buf.release();
            }
        }
        assertTrue(text.length() > 0, "握手必须写出 HTTP 响应");
        return text.toString();
    }

    private ByteBuf readOutboundBytes() {
        Object outbound = channel.readOutbound();
        return assertInstanceOf(ByteBuf.class, outbound);
    }

    /** 只读第一段 outbound（握手响应的首段），后续写出的帧仍留在队列里。 */
    private String firstOutboundText() {
        ByteBuf buf = readOutboundBytes();
        try {
            return buf.toString(StandardCharsets.UTF_8);
        } finally {
            buf.release();
        }
    }

    /** 读空 outbound，返回最后一段（中间段是响应尾部之类，直接释放）。 */
    @Nullable
    private ByteBuf drainToLastOutbound() {
        ByteBuf last = null;
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            ByteBuf buf = assertInstanceOf(ByteBuf.class, outbound);
            if (last != null) {
                last.release();
            }
            last = buf;
        }
        return last;
    }

    @Test
    void unsupportedVersion_responds426WithoutUpgrading() {
        WebSocketHandler handler = mock(WebSocketHandler.class);
        newPipeline(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler)));

        // 只有 7/8/13（以及 0）被 Netty 的握手工厂支持，6 会走「版本不支持」分支
        channel.writeInbound(upgradeRequest("6", true));

        assertTrue(readOutboundText().startsWith("HTTP/1.1 426 Upgrade Required"));
        assertNull(WebSocketRoutingHandler.sessionOf(channel), "版本不支持时不能建会话");
        assertNull(channel.pipeline().get(SslHandler.class));
    }

    @Test
    void afterConnectionEstablishedThrows_closesSessionWithServerError() throws Exception {
        WebSocketHandler handler = mock(WebSocketHandler.class);
        doThrow(new IllegalStateException("boom")).when(handler).afterConnectionEstablished(any());
        newPipeline(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler)));

        channel.writeInbound(upgradeRequest("13", true));

        assertTrue(firstOutboundText().startsWith("HTTP/1.1 101 Switching Protocols"));
        ByteBuf closeFrame = drainToLastOutbound();
        assertNotNull(closeFrame, "建连回调失败后必须主动发 Close");
        try {
            assertEquals(0x88, closeFrame.getUnsignedByte(0), "建连回调失败后必须主动发 Close，否则连接会半开");
            assertEquals(CloseStatus.SERVER_ERROR.getCode(), closeFrame.getUnsignedShort(2));
            assertEquals(2, closeFrame.getUnsignedByte(1), "SERVER_ERROR 无原因文本，payload 只有状态码");
        } finally {
            closeFrame.release();
        }
        // 异常不能逃逸到 pipeline 之外（否则会被 exceptionCaught 二次处理）：checkException 若有异常会直接抛出
        channel.checkException();
    }

    @Test
    void missingHostHeader_buildsUrlFromLocalAddress() {
        WebSocketHandler handler = mock(WebSocketHandler.class);
        // EmbeddedChannel 默认 localAddress 是 EmbeddedSocketAddress，会让 URL 拼装强转失败；
        // 这里覆写成真实地址，模拟「HTTP/1.0 客户端不带 Host 头」
        channel = new EmbeddedChannel() {
            @Override
            public SocketAddress localAddress() {
                return new InetSocketAddress("127.0.0.1", 18080);
            }
        };
        channel.pipeline().addLast(new HttpServerCodec());
        channel.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
        channel.pipeline().addLast(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler)));

        channel.writeInbound(upgradeRequest("13", false));

        assertTrue(readOutboundText().startsWith("HTTP/1.1 101 Switching Protocols"), "无 Host 头时用本地地址拼 ws:// 仍应握手成功");
    }

    @Test
    void malformedRequestUri_fallsBackToRootSessionUri() {
        WebSocketHandler handler = mock(WebSocketHandler.class);
        // 路径本身要能匹配上 handler（否则请求会被透传，压根不进握手）；
        // 空格是 URI.create 的非法字符，但 QueryStringDecoder 只截取不校验，故能走到建 session 那一步
        newPipeline(new WebSocketRoutingHandler(Collections.singletonMap("/ws x", handler)));

        // Netty 的 HttpRequest 在构造器与 setUri 上都会校验请求行，非法字符只能在 uri() 上覆写出来
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws",
                Unpooled.buffer(0)) {
            @Override
            public String uri() {
                return "/ws x";
            }
        };
        req.headers().set(HttpHeaderNames.HOST, "localhost");
        req.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        req.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");

        channel.writeInbound(req);

        assertTrue(readOutboundText().startsWith("HTTP/1.1 101 Switching Protocols"), "非法 URI 不能让握手失败");
        NettyWebSocketSession session = WebSocketRoutingHandler.sessionOf(channel);
        assertNotNull(session);
        assertEquals(URI.create("/"), session.getUri(), "非法 URI 必须退化为根路径");
    }

    @Test
    void tlsConnection_reportsPeerPrincipalAndUsesWssScheme() throws Exception {
        WebSocketHandler handler = mock(WebSocketHandler.class);
        Principal principal = () -> "CN=client";
        SslHandler sslHandler = mockSslHandler(principal);

        newPipeline(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler)));
        // 挂在 routing handler 之后：入站请求仍先被 routing 拦截，但 pipeline.get 能找到它
        channel.pipeline().addLast("ssl", sslHandler);

        channel.writeInbound(upgradeRequest("13", true));

        assertTrue(readOutboundText().startsWith("HTTP/1.1 101 Switching Protocols"));
        NettyWebSocketSession session = WebSocketRoutingHandler.sessionOf(channel);
        assertNotNull(session);
        assertSame(principal, session.getPrincipal(), "TLS 客户端证书必须透出为 session principal");
    }

    @Test
    void tlsWithoutClientCertificate_reportsNullPrincipal() throws Exception {
        WebSocketHandler handler = mock(WebSocketHandler.class);
        // 单向 TLS：服务端没有客户端证书，getPeerPrincipal 抛 SSLPeerUnverifiedException
        SslHandler sslHandler = mockSslHandlerThrowing(new SSLPeerUnverifiedException("no client cert"));

        newPipeline(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler)));
        channel.pipeline().addLast("ssl", sslHandler);

        channel.writeInbound(upgradeRequest("13", true));

        assertTrue(readOutboundText().startsWith("HTTP/1.1 101 Switching Protocols"));
        NettyWebSocketSession session = WebSocketRoutingHandler.sessionOf(channel);
        assertNotNull(session);
        assertNull(session.getPrincipal(), "取不到客户端证书不能中断握手");
    }

    /**
     * 一个能参与 pipeline 的 SslHandler 桩：{@code write} 必须转发并 flush，否则握手响应既不出站也读不到。
     */
    private static SslHandler mockSslHandler(Principal principal) throws Exception {
        SSLSession sslSession = mock(SSLSession.class);
        when(sslSession.getPeerPrincipal()).thenReturn(principal);
        return sslHandlerStub(sslSession);
    }

    private static SslHandler mockSslHandlerThrowing(Exception failure) throws Exception {
        SSLSession sslSession = mock(SSLSession.class);
        when(sslSession.getPeerPrincipal()).thenThrow(failure);
        return sslHandlerStub(sslSession);
    }

    private static SslHandler sslHandlerStub(SSLSession sslSession) throws Exception {
        SSLEngine engine = mock(SSLEngine.class);
        when(engine.getSession()).thenReturn(sslSession);
        SslHandler sslHandler = mock(SslHandler.class);
        when(sslHandler.engine()).thenReturn(engine);
        doAnswer(invocation -> invocation.getArgument(0, ChannelHandlerContext.class)
                .writeAndFlush(invocation.getArgument(1), invocation.getArgument(2, ChannelPromise.class)))
                        .when(sslHandler).write(any(), any(), any());
        return sslHandler;
    }

    @Test
    void heartbeat_skippedWhenSessionClosed() throws Exception {
        WebSocketHandler handler = mock(WebSocketHandler.class);
        newPipeline(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null, -1, 1));

        channel.writeInbound(upgradeRequest("13", true));
        assertTrue(readOutboundText().startsWith("HTTP/1.1 101 Switching Protocols"));

        // 业务侧已关闭会话（但 TCP 尚未断开）：心跳不能再往已关闭的会话上写
        WebSocketRoutingHandler.sessionOf(channel).setOpen(false);
        Thread.sleep(20);
        channel.runScheduledPendingTasks();

        assertNull(channel.readOutbound(), "会话已关闭时不得继续发心跳");
    }

    @Test
    void heartbeat_sendsPingOnSchedule() throws Exception {
        WebSocketHandler handler = mock(WebSocketHandler.class);
        newPipeline(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null, -1, 1));

        channel.writeInbound(upgradeRequest("13", true));
        assertTrue(readOutboundText().startsWith("HTTP/1.1 101 Switching Protocols"));

        // EmbeddedEventLoop 的定时任务由 runScheduledPendingTasks 驱动；先等过首个周期
        Thread.sleep(20);
        channel.runScheduledPendingTasks();

        ByteBuf pingFrame = readOutboundBytes();
        try {
            assertEquals(0x89, pingFrame.getUnsignedByte(0), "心跳间隔到点必须发 Ping，否则空闲连接会被中间设备掐断");
            assertEquals(0, pingFrame.getUnsignedByte(1), "空载荷 Ping");
        } finally {
            pingFrame.release();
        }
    }
}
