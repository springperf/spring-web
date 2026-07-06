package io.springperf.web.websocket.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.springperf.web.core.cors.CorsUtils;
import io.springperf.web.server.NettyHttpHandler;
import io.springperf.web.util.PathPatternUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.util.RouteMatcher;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Netty pipeline handler：拦截 HTTP WebSocket 升级请求并委托 Spring。
 *
 * <p>放置在 {@code HttpObjectAggregator} 之前，拦截带 {@code Upgrade: websocket} 头的
 * HTTP 请求。匹配到已注册路径后：</p>
 * <ol>
 *   <li>使用 {@link WebSocketServerHandshaker} 完成 HTTP 升级握手</li>
 *   <li>将 pipeline 从 HTTP 模式切换为 WebSocket 帧模式</li>
 *   <li>创建 {@link NettyWebSocketSession}，调用 Spring 的 {@link WebSocketHandler}</li>
 *   <li>后续 WebSocket 帧直接委托给 Spring handler</li>
 * </ol>
 *
 * <p>非 WebSocket 升级请求透传，不影响正常 HTTP 处理。</p>
 *
 * @author huangcanda
 * @since 1.0.4
 */
@Slf4j
@ChannelHandler.Sharable
public class WebSocketRoutingHandler extends ChannelInboundHandlerAdapter {

    private static final AttributeKey<NettyWebSocketSession> SESSION_KEY =
            AttributeKey.valueOf("ws.session");
    private static final AttributeKey<WebSocketHandler> HANDLER_KEY =
            AttributeKey.valueOf("ws.handler");

    private static final String WS_DECODER = "ws-decoder";
    private static final String WS_ENCODER = "ws-encoder";

    private final Map<String, WebSocketHandler> handlerMap;
    private final String subProtocols;
    private final boolean allowExtensions;
    private final List<String> allowedOrigins;
    private final long idleTimeout;
    private final long heartbeatInterval;
    @Nullable
    private final io.springperf.web.websocket.WebSocketHandlerRegistry registry;

    public WebSocketRoutingHandler(Map<String, WebSocketHandler> handlerMap) {
        this(handlerMap, null, false, null, -1, -1, null);
    }

    public WebSocketRoutingHandler(Map<String, WebSocketHandler> handlerMap,
                                    String subProtocols, boolean allowExtensions) {
        this(handlerMap, subProtocols, allowExtensions, null, -1, -1, null);
    }

    public WebSocketRoutingHandler(Map<String, WebSocketHandler> handlerMap,
                                    String subProtocols, boolean allowExtensions,
                                    List<String> allowedOrigins) {
        this(handlerMap, subProtocols, allowExtensions, allowedOrigins, -1, -1, null);
    }

    public WebSocketRoutingHandler(Map<String, WebSocketHandler> handlerMap,
                                    String subProtocols, boolean allowExtensions,
                                    List<String> allowedOrigins, long idleTimeout) {
        this(handlerMap, subProtocols, allowExtensions, allowedOrigins, idleTimeout, -1, null);
    }

    public WebSocketRoutingHandler(Map<String, WebSocketHandler> handlerMap,
                                    String subProtocols, boolean allowExtensions,
                                    List<String> allowedOrigins, long idleTimeout,
                                    long heartbeatInterval) {
        this(handlerMap, subProtocols, allowExtensions, allowedOrigins, idleTimeout, heartbeatInterval, null);
    }

    public WebSocketRoutingHandler(Map<String, WebSocketHandler> handlerMap,
                                    String subProtocols, boolean allowExtensions,
                                    List<String> allowedOrigins, long idleTimeout,
                                    long heartbeatInterval,
                                    @Nullable io.springperf.web.websocket.WebSocketHandlerRegistry registry) {
        this.handlerMap = handlerMap;
        this.subProtocols = subProtocols;
        this.allowExtensions = allowExtensions;
        this.allowedOrigins = allowedOrigins;
        this.idleTimeout = idleTimeout;
        this.heartbeatInterval = heartbeatInterval;
        this.registry = registry;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) msg;
            String upgrade = req.headers().get(HttpHeaderNames.UPGRADE);
            if ("websocket".equalsIgnoreCase(upgrade)) {
                handleWebSocketUpgrade(ctx, req);
                return;
            }
        }
        if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, (WebSocketFrame) msg);
            return;
        }
        ctx.fireChannelRead(msg);
    }

    private void handleWebSocketUpgrade(ChannelHandlerContext ctx, HttpRequest req) {
        String path = new QueryStringDecoder(req.uri()).path();
        PathMatchResult result = resolveHandler(path);
        if (result == null) {
            ctx.fireChannelRead(req);
            return;
        }

        // Origin 校验：优先使用 per-path 配置，回退到全局默认
        List<String> effectiveOrigins = resolveOrigins(result.registration);
        if (!checkOrigin(req, ctx, effectiveOrigins)) {
            sendForbiddenResponse(ctx, req);
            return;
        }

        String wsUrl = buildWebSocketUrl(ctx, req, path);
        String effectiveSubProtocols = resolveSubProtocols(result.registration);
        boolean effectiveExtensions = resolveAllowExtensions(result.registration);
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                wsUrl, effectiveSubProtocols, effectiveExtensions);
        WebSocketServerHandshaker handshaker = factory.newHandshaker(req);

        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return;
        }

        handshaker.handshake(ctx.channel(), (FullHttpRequest) req)
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        log.error("WebSocket handshake failed for {}", path, future.cause());
                        ctx.close();
                        return;
                    }
                    afterHandshakeSuccess(ctx, req, path, handshaker, result);
                });
    }

    private void afterHandshakeSuccess(ChannelHandlerContext ctx, HttpRequest req,
                                        String path, WebSocketServerHandshaker handshaker,
                                        PathMatchResult result) {
        // 构建 URI 和 Headers
        URI sessionUri = buildSessionUri(req);
        SpringHeadersAdapter springHeaders = new SpringHeadersAdapter(req.headers());
        InetSocketAddress localAddr = (InetSocketAddress) ctx.channel().localAddress();
        InetSocketAddress remoteAddr = (InetSocketAddress) ctx.channel().remoteAddress();

        // 从 TLS 提取客户端证书 Principal
        Principal principal = extractTlsPrincipal(ctx);
        NettyWebSocketSession session = new NettyWebSocketSession(
                ctx.channel(), sessionUri, springHeaders,
                handshaker.selectedSubprotocol(), localAddr, remoteAddr, principal,
                result.handler);

        // 将路径变量存入 session attributes（如 roomId=123）
        session.getAttributes().putAll(result.uriVariables);

        // 切换 pipeline：移除 HTTP handler，添加 WebSocket 帧编解码器
        ChannelPipeline pipeline = ctx.pipeline();
        String currentName = ctx.name();

        // 空闲超时检测（支持 per-path 覆盖）
        long effectiveIdleTimeout = resolveIdleTimeout(result.registration);
        if (effectiveIdleTimeout > 0) {
            pipeline.addBefore(currentName, "ws-idle",
                    new IdleStateHandler(effectiveIdleTimeout, effectiveIdleTimeout, effectiveIdleTimeout,
                            java.util.concurrent.TimeUnit.MILLISECONDS));
        }

        // 从 session 的 sizeLimit 决定 decoder 的最大帧载荷长度
        int maxFrameSize = Math.max(session.getTextMessageSizeLimit(), session.getBinaryMessageSizeLimit());
        boolean effectiveExtensions = resolveAllowExtensions(result.registration);
        pipeline.addBefore(currentName, WS_DECODER,
                new WebSocket08FrameDecoder(false, effectiveExtensions, maxFrameSize));
        pipeline.addBefore(currentName, WS_ENCODER, new WebSocket08FrameEncoder(false));
        removeHttpHandlers(pipeline);

        ctx.channel().attr(SESSION_KEY).set(session);
        ctx.channel().attr(HANDLER_KEY).set(result.handler);

        try {
            result.handler.afterConnectionEstablished(session);
        } catch (Exception e) {
            log.error("WebSocketHandler.afterConnectionEstablished failed", e);
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ex) {
                log.debug("session.close(SERVER_ERROR) failed", ex);
            }
        }

        // 主动心跳保活（支持 per-path 覆盖）
        long effectiveHeartbeat = resolveHeartbeatInterval(result.registration);
        if (effectiveHeartbeat > 0 && session.isOpen()) {
            java.util.concurrent.ScheduledFuture<?> hbFuture = ctx.channel().eventLoop()
                    .scheduleAtFixedRate(() -> {
                        if (session.isOpen() && ctx.channel().isWritable()) {
                            ctx.writeAndFlush(new PingWebSocketFrame());
                        }
                    }, effectiveHeartbeat, effectiveHeartbeat, java.util.concurrent.TimeUnit.MILLISECONDS);
            ctx.channel().closeFuture().addListener(f -> hbFuture.cancel(false));
        }
    }

    private void removeHttpHandlers(ChannelPipeline pipeline) {
        safeRemove(pipeline, NettyHttpHandler.class);
        safeRemove(pipeline, io.netty.handler.stream.ChunkedWriteHandler.class);
        safeRemove(pipeline, io.netty.handler.codec.http.HttpObjectAggregator.class);
        safeRemove(pipeline, io.netty.handler.codec.http.HttpServerCodec.class);
    }

    private static void safeRemove(ChannelPipeline pipeline, Class<? extends ChannelHandler> handlerType) {
        if (pipeline.get(handlerType) != null) {
            pipeline.remove(handlerType);
        }
    }

    private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
        NettyWebSocketSession session = ctx.channel().attr(SESSION_KEY).get();
        WebSocketHandler handler = ctx.channel().attr(HANDLER_KEY).get();
        if (handler == null || session == null) {
            ctx.close();
            return;
        }

        try {
            if (frame instanceof TextWebSocketFrame) {
                handler.handleMessage(session, new TextMessage(((TextWebSocketFrame) frame).text()));
            } else if (frame instanceof BinaryWebSocketFrame) {
                ByteBuf buf = frame.content();
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                handler.handleMessage(session, new BinaryMessage(ByteBuffer.wrap(bytes)));
            } else if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            } else if (frame instanceof PongWebSocketFrame) {
                handler.handleMessage(session, new PongMessage(ByteBuffer.wrap(new byte[0])));
            } else if (frame instanceof CloseWebSocketFrame) {
                CloseWebSocketFrame close = (CloseWebSocketFrame) frame;
                int code = close.statusCode() >= 0 ? close.statusCode() : 1005;
                CloseStatus status = new CloseStatus(code, close.reasonText());
                session.setOpen(false);
                try {
                    handler.afterConnectionClosed(session, status);
                } catch (Exception e) {
                    log.error("afterConnectionClosed failed", e);
                }
                // RFC 6455 §7.4.1: 服务端必须回显 Close 帧，再关闭 TCP
                ctx.writeAndFlush(close.retain())
                        .addListener(ChannelFutureListener.CLOSE);
                return; // frame released in finally, retained copy lives for write
            }
        } catch (Exception e) {
            log.error("WebSocket handler error", e);
            try {
                handler.handleTransportError(session, e);
            } catch (Exception ex) {
                log.error("TransportError handler failed", ex);
            }
        } finally {
            frame.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        WebSocketHandler handler = ctx.channel().attr(HANDLER_KEY).get();
        NettyWebSocketSession session = ctx.channel().attr(SESSION_KEY).get();
        if (handler != null && session != null) {
            try {
                handler.handleTransportError(session, cause);
            } catch (Exception e) {
                log.error("TransportError handler failed", e);
            }
        }
        ctx.close();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable()) {
            NettyWebSocketSession.drainBackpressureQueue(ctx.channel());
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            WebSocketHandler handler = ctx.channel().attr(HANDLER_KEY).get();
            NettyWebSocketSession session = ctx.channel().attr(SESSION_KEY).get();
            if (handler != null && session != null) {
                log.warn("WebSocket idle timeout, closing session {}", session.getId());
                try {
                    handler.handleTransportError(session,
                            new java.io.IOException("Connection idle timeout"));
                } catch (Exception e) {
                    log.error("Idle timeout handler failed", e);
                }
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (IOException e) {
                    log.debug("session.close(SESSION_NOT_RELIABLE) failed", e);
                }
            }
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        WebSocketHandler handler = ctx.channel().attr(HANDLER_KEY).get();
        NettyWebSocketSession session = ctx.channel().attr(SESSION_KEY).get();
        if (handler != null && session != null && session.isOpen()) {
            session.setOpen(false);
            try {
                handler.afterConnectionClosed(session, CloseStatus.GOING_AWAY);
            } catch (Exception e) {
                log.error("afterConnectionClosed failed", e);
            }
        }
        ctx.fireChannelInactive();
    }

    // ---- per-path 配置解析（有 per-path 覆盖则使用，否则回退全局） ----

    @Nullable
    private List<String> resolveOrigins(
            @Nullable io.springperf.web.websocket.WebSocketHandlerRegistration reg) {
        if (reg != null && reg.getAllowedOrigins() != null) return reg.getAllowedOrigins();
        return allowedOrigins;
    }

    private String resolveSubProtocols(
            @Nullable io.springperf.web.websocket.WebSocketHandlerRegistration reg) {
        if (reg != null && reg.getSubProtocols() != null) return reg.getSubProtocols();
        return subProtocols;
    }

    private boolean resolveAllowExtensions(
            @Nullable io.springperf.web.websocket.WebSocketHandlerRegistration reg) {
        if (reg != null && reg.getAllowExtensions() != null) return reg.getAllowExtensions();
        return allowExtensions;
    }

    private long resolveIdleTimeout(
            @Nullable io.springperf.web.websocket.WebSocketHandlerRegistration reg) {
        if (reg != null && reg.getIdleTimeout() != null) return reg.getIdleTimeout();
        return idleTimeout;
    }

    private long resolveHeartbeatInterval(
            @Nullable io.springperf.web.websocket.WebSocketHandlerRegistration reg) {
        if (reg != null && reg.getHeartbeatInterval() != null) return reg.getHeartbeatInterval();
        return heartbeatInterval;
    }

    /**
     * 从 SslHandler 提取 TLS 客户端证书 Principal。
     * 无 TLS 或无客户端证书时返回 null。
     */
    @Nullable
    private static Principal extractTlsPrincipal(ChannelHandlerContext ctx) {
        SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
        if (sslHandler == null) {
            return null;
        }
        try {
            return sslHandler.engine().getSession().getPeerPrincipal();
        } catch (Exception e) {
            log.debug("No TLS client certificate available", e);
            return null;
        }
    }

    /**
     * 校验 WebSocket 握手请求的 Origin 头。
     * <p>未配置 allowedOrigins 时跳过校验（兼容非浏览器客户端）。
     * 复用 {@link CorsUtils} 的 origin 解析逻辑。</p>
     *
     * @param effectiveOrigins 优先使用 per-path 配置，null 时使用全局默认
     */
    private boolean checkOrigin(HttpRequest req, ChannelHandlerContext ctx,
                                @Nullable List<String> effectiveOrigins) {
        List<String> origins = effectiveOrigins != null ? effectiveOrigins : allowedOrigins;
        if (origins == null || origins.isEmpty()) {
            return true;
        }
        String origin = req.headers().get(HttpHeaderNames.ORIGIN);
        if (origin == null) {
            return true; // 非浏览器客户端无 Origin 头
        }
        if (origins.contains("*")) {
            return true;
        }
        // 与配置的允许来源逐一匹配
        for (String allowed : origins) {
            if (allowed.equals(origin)) {
                return true;
            }
        }
        log.warn("WebSocket connection rejected: origin '{}' not allowed", origin);
        return false;
    }

    private static void sendForbiddenResponse(ChannelHandlerContext ctx, HttpRequest req) {
        io.netty.handler.codec.http.DefaultHttpResponse resp =
                new io.netty.handler.codec.http.DefaultHttpResponse(
                        req.protocolVersion(), HttpResponseStatus.FORBIDDEN);
        if (HttpUtil.isKeepAlive(req)) {
            resp.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONNECTION, "close");
        }
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    private static final class PathMatchResult {
        final WebSocketHandler handler;
        final io.springperf.web.websocket.WebSocketHandlerRegistration registration;
        final Map<String, String> uriVariables;

        PathMatchResult(WebSocketHandler handler,
                        io.springperf.web.websocket.WebSocketHandlerRegistration registration,
                        Map<String, String> uriVariables) {
            this.handler = handler;
            this.registration = registration;
            this.uriVariables = uriVariables;
        }
    }

    private PathMatchResult resolveHandler(String path) {
        // 1. 精确匹配（快速路径，无路径变量）
        WebSocketHandler handler = handlerMap.get(path);
        if (handler != null) {
            io.springperf.web.websocket.WebSocketHandlerRegistration reg =
                    registry != null ? registry.getRegistration(path) : null;
            return new PathMatchResult(handler, reg, Collections.emptyMap());
        }

        // 2. RouteMatcher 模式匹配（支持 /** 通配、{roomId} 路径变量等）
        RouteMatcher routeMatcher = PathPatternUtils.getPatternRouteMatcher();
        RouteMatcher.Route route = routeMatcher.parseRoute(path);
        for (Map.Entry<String, WebSocketHandler> entry : handlerMap.entrySet()) {
            Map<String, String> variables = routeMatcher.matchAndExtract(entry.getKey(), route);
            if (variables != null) {
                io.springperf.web.websocket.WebSocketHandlerRegistration reg =
                        registry != null ? registry.getRegistration(entry.getKey()) : null;
                return new PathMatchResult(entry.getValue(), reg, variables);
            }
        }

        return null;
    }

    private static String buildWebSocketUrl(ChannelHandlerContext ctx, HttpRequest req, String path) {
        String host = req.headers().get(HttpHeaderNames.HOST);
        if (host == null) {
            InetSocketAddress addr = (InetSocketAddress) ctx.channel().localAddress();
            host = addr.getHostString() + ":" + addr.getPort();
        }
        String scheme = ctx.pipeline().get(SslHandler.class) != null ? "wss" : "ws";
        return scheme + "://" + host + path;
    }

    private URI buildSessionUri(HttpRequest req) {
        try {
            return URI.create(req.uri());
        } catch (Exception e) {
            return URI.create("/");
        }
    }
}
