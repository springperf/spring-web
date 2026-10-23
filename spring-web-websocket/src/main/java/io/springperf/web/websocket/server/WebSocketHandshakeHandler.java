package io.springperf.web.websocket.server;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtension;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;
import io.springperf.web.server.NettyHttpHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.CloseStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 升级握手：Origin 准入、Netty 握手器协商、pipeline 切换、会话建立与心跳。
 * <p>
 * 从 {@link WebSocketRoutingHandler} 拆出：路由关心「这个请求该交给谁」，握手关心「如何把它变成一条 WebSocket 连接」。 拆开后 pipeline 装配与引用计数的改动不再和路由匹配混在一起。
 * </p>
 * <p>
 * 无状态（除不可变的准入策略外）：连接状态统一挂在 {@link WebSocketRoutingHandler#CONNECTION_KEY} 上，只在 EventLoop 线程读写。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.8
 */
@Slf4j
final class WebSocketHandshakeHandler {

    /**
     * {@code WebSocketServerHandshaker.handshake()} 自己插入的帧编解码器名字（Netty 内部约定，无公开常量）。 扩展编解码器必须相对它们插入：它们才是真正做「字节 ↔
     * 帧」转换的那一对。
     */
    private static final String NETTY_WS_DECODER = "wsdecoder";
    private static final String NETTY_WS_ENCODER = "wsencoder";
    private static final String WS_DEFLATE_DECODER = "ws-deflate-decoder";
    private static final String WS_DEFLATE_ENCODER = "ws-deflate-encoder";

    private final WebSocketUpgradePolicy policy;

    WebSocketHandshakeHandler(String subProtocols, boolean allowExtensions, @Nullable List<String> allowedOrigins,
            long idleTimeout, long heartbeatInterval) {
        this.policy = new WebSocketUpgradePolicy(subProtocols, allowExtensions, allowedOrigins, idleTimeout,
                heartbeatInterval);
    }

    /** 执行升级握手；请求引用的所有权在本方法内转移（成功由握手器接管，失败自行释放）。 */
    void upgrade(ChannelHandlerContext ctx, HttpRequest req, WebSocketPathMatch match) {
        String path = match.path;
        // Origin 校验：优先使用 per-path 配置，回退到全局默认；handler 自身（如 JSR Configurator#checkOrigin）再判一次
        if (!policy.checkOrigin(req, match.registration) || !WebSocketUpgradePolicy.checkHandlerOrigin(match.handler,
                req.headers().get(HttpHeaderNames.ORIGIN))) {
            WebSocketUpgradePolicy.sendForbiddenResponse(ctx, req);
            return;
        }

        String wsUrl = buildWebSocketUrl(ctx, req, path);
        String effectiveSubProtocols = policy.resolveSubProtocols(match.registration);
        boolean extensionOffered = policy.resolveAllowExtensions(match.registration);
        // 只有真正协商成功才让帧解码器接受 RSV 位：仅仅 allowExtensions=true 而客户端没提扩展时，
        // 客户端发来的 RSV1 压缩帧会被当成普通帧交给业务（静默乱码）。
        WebSocketServerExtension negotiatedExtension = extensionOffered
                ? WebSocketExtensionNegotiator.negotiate(req.headers())
                : null;
        boolean rsvAllowed = negotiatedExtension != null;
        // 帧上限交给握手器创建的解码器：它才是真正做「字节 → 帧」的那一环。
        // 之前用 3 参构造（隐式 64KB）再加一个自有 decoder，导致 per-path 上限 > 64KB 时被前置 decoder 拦掉。
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(wsUrl, effectiveSubProtocols,
                rsvAllowed, policy.resolveMaxFrameSize(match.registration));
        WebSocketServerHandshaker handshaker = factory.newHandshaker(req);

        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            // 拒绝路径不将请求透传下游，释放 FullHttpRequest 引用（含 content ByteBuf）
            ReferenceCountUtil.release(req);
            return;
        }

        try {
            ChannelFuture handshake;
            if (negotiatedExtension != null) {
                // 协商结果必须写在握手响应里，客户端才会启用压缩。本框架是手写握手（不经过
                // WebSocketServerProtocolHandler，也就不会触发 Netty 的 HANDSHAKE_COMPLETE 事件），
                // 因此这里自己写响应头、握手完成后再自己装编解码器。
                io.netty.handler.codec.http.HttpHeaders responseHeaders = new DefaultHttpHeaders().set(
                        HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS,
                        WebSocketExtensionNegotiator.responseHeaderValue(negotiatedExtension.newReponseData()));
                handshake = handshaker.handshake(ctx.channel(), (FullHttpRequest) req, responseHeaders,
                        ctx.channel().newPromise());
            } else {
                handshake = handshaker.handshake(ctx.channel(), (FullHttpRequest) req);
            }
            handshake.addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    log.error("WebSocket handshake failed for {}", path, future.cause());
                    // 握手失败：请求不再被使用，释放调用方持有的原始引用
                    // （netty handshake 内部 retain 的引用由 WebSocketServerHandshaker$2
                    // 在 fireChannelRead 时释放，此处释放原始引用）。
                    ReferenceCountUtil.release(req);
                    ctx.close();
                    return;
                }
                afterHandshakeSuccess(ctx, req, handshaker, match, negotiatedExtension);
            });
        } catch (Exception e) {
            // handshake() 同步抛异常时也要释放原始引用。注意与上一条的区别：pipeline 缺少
            // HttpRequestDecoder/HttpServerCodec 时 Netty 并不抛异常，而是把返回的 promise
            // 置失败——那条路径由上面的 listener 释放。这里覆盖的是握手内部的 pipeline 操作
            // 本身抛错，例如同名 wsdecoder/wsencoder 已存在时 addBefore 抛 ChannelPipelineException。
            // 此类异常若不在此释放，会冒泡到 exceptionCaught，而那里只 ctx.close() 不 release，
            // 每次泄漏一个 FullHttpRequest（含 content ByteBuf）。
            log.error("WebSocket handshake threw synchronously for {}", path, e);
            ReferenceCountUtil.release(req);
            ctx.close();
        }
    }

    private void afterHandshakeSuccess(ChannelHandlerContext ctx, HttpRequest req, WebSocketServerHandshaker handshaker,
            WebSocketPathMatch match, @Nullable WebSocketServerExtension negotiatedExtension) {
        // 构建 URI 和 Headers
        URI sessionUri = buildSessionUri(req);
        HttpHeaders springHeaders = toSpringHeaders(req.headers());
        // SocketAddress 不保证是 InetSocketAddress（如 EmbeddedChannel 的 EmbeddedSocketAddress、
        // 未绑定连接时为 null），强转会 CCE 中断握手完成回调。防御性转换，非 InetSocketAddress 时
        // 返回 null（真实 TCP 连接下始终是 InetSocketAddress，行为不变）。
        InetSocketAddress localAddr = toInetSocketAddress(ctx.channel().localAddress());
        InetSocketAddress remoteAddr = toInetSocketAddress(ctx.channel().remoteAddress());

        // 从 TLS 提取客户端证书 Principal
        Principal principal = extractTlsPrincipal(ctx);
        NettyWebSocketSession session = new NettyWebSocketSession(ctx.channel(), sessionUri, springHeaders,
                handshaker.selectedSubprotocol(), localAddr, remoteAddr, principal, match.handler);

        // 将路径变量存入 session attributes（如 roomId=123）
        session.getAttributes().putAll(match.uriVariables);

        // getExtensions() 是业务（以及 JSR 的 getNegotiatedExtensions）判断「压缩是否生效」的唯一入口：
        // 协商成功必须上报，否则框架内部按扩展装了压缩器、业务侧却以为没协商。
        if (negotiatedExtension != null) {
            session.setExtensions(Collections.singletonList(
                    WebSocketExtensionNegotiator.toSpringExtension(negotiatedExtension.newReponseData())));
        }

        // per-path 帧上限覆盖（L14）：注册时配置 messageSizeLimit 则覆盖 session 默认 8KB/64KB，
        // 随后 decoder 的最大帧载荷长度取自覆盖后的值。
        long effectiveMessageSizeLimit = policy.resolveMessageSizeLimit(match.registration);
        if (effectiveMessageSizeLimit > 0) {
            session.setTextMessageSizeLimit((int) effectiveMessageSizeLimit);
            session.setBinaryMessageSizeLimit((int) effectiveMessageSizeLimit);
        }

        // 切换 pipeline：移除 HTTP handler，添加 WebSocket 帧编解码器
        ChannelPipeline pipeline = ctx.pipeline();
        String currentName = ctx.name();

        // 空闲超时检测（支持 per-path 覆盖）
        long effectiveIdleTimeout = policy.resolveIdleTimeout(match.registration);
        if (effectiveIdleTimeout > 0) {
            pipeline.addBefore(currentName, "ws-idle", new IdleStateHandler(effectiveIdleTimeout, effectiveIdleTimeout,
                    effectiveIdleTimeout, TimeUnit.MILLISECONDS));
        }

        // 帧编解码器由 WebSocketServerHandshaker.handshake() 自己装好（Netty 的 "wsdecoder"/"wsencoder"），
        // 这里不再重复添加一套：以前额外加的 ws-decoder/ws-encoder 从不参与字节↔帧转换，只是每帧多跑一层空转。
        if (negotiatedExtension != null) {
            // 入站：字节 → 帧解码(wsdecoder) → 解压 → 业务；出站：业务 → 压缩 → 帧编码(wsencoder) → 字节。
            // 故解压器插在 wsdecoder 之后、压缩器插在 wsencoder 之后（更靠近 tail，出站先经过它）。
            pipeline.addAfter(NETTY_WS_DECODER, WS_DEFLATE_DECODER, negotiatedExtension.newExtensionDecoder());
            pipeline.addAfter(NETTY_WS_ENCODER, WS_DEFLATE_ENCODER, negotiatedExtension.newExtensionEncoder());
        }
        removeHttpHandlers(pipeline);

        WebSocketRoutingHandler.WsConnection connection = new WebSocketRoutingHandler.WsConnection(session,
                match.handler);
        ctx.channel().attr(WebSocketRoutingHandler.CONNECTION_KEY).set(connection);

        try {
            match.handler.afterConnectionEstablished(session);
        } catch (Exception e) {
            log.error("WebSocketHandler.afterConnectionEstablished failed", e);
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ex) {
                log.debug("session.close(SERVER_ERROR) failed", ex);
            }
        }

        // 主动心跳保活（支持 per-path 覆盖）
        long effectiveHeartbeat = policy.resolveHeartbeatInterval(match.registration);
        if (effectiveHeartbeat > 0 && session.isOpen()) {
            ScheduledFuture<?> hbFuture = ctx.channel().eventLoop().scheduleAtFixedRate(() -> {
                if (session.isOpen() && ctx.channel().isWritable()) {
                    ctx.writeAndFlush(new PingWebSocketFrame());
                }
            }, effectiveHeartbeat, effectiveHeartbeat, TimeUnit.MILLISECONDS);
            ctx.channel().closeFuture().addListener(f -> hbFuture.cancel(false));
        }

        // 释放握手请求的原始引用。netty handshake 内部 retain 的引用由
        // WebSocketServerHandshaker$2 在 fireChannelRead 时消费释放；此处释放调用方
        // 持有的原始引用（HttpObjectAggregator fireChannelRead 转移的所有权）。
        // 修复前不释放：每次 WS 握手泄漏 1 个 FullHttpRequest 引用，触发 Netty LEAK 检测。
        ReferenceCountUtil.release(req);
    }

    private void removeHttpHandlers(ChannelPipeline pipeline) {
        safeRemove(pipeline, NettyHttpHandler.class);
        safeRemove(pipeline, io.netty.handler.stream.ChunkedWriteHandler.class);
        safeRemove(pipeline, io.netty.handler.codec.http.HttpObjectAggregator.class);
        safeRemove(pipeline, io.netty.handler.codec.http.HttpServerCodec.class);
        // HTTP 层 ReadTimeoutHandler（Http2ChannelInitializer 在 readTimeout>0 时添加）在 WS
        // 升级后必须移除，否则空闲 WebSocket 会被 ReadTimeoutException 误杀。
        safeRemove(pipeline, io.netty.handler.timeout.ReadTimeoutHandler.class);
    }

    private static void safeRemove(ChannelPipeline pipeline, Class<? extends ChannelHandler> handlerType) {
        if (pipeline.get(handlerType) != null) {
            pipeline.remove(handlerType);
        }
    }

    @Nullable
    private static InetSocketAddress toInetSocketAddress(SocketAddress address) {
        return address instanceof InetSocketAddress ? (InetSocketAddress) address : null;
    }

    /**
     * 从 SslHandler 提取 TLS 客户端证书 Principal。 无 TLS 或无客户端证书时返回 null。
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

    private static String buildWebSocketUrl(ChannelHandlerContext ctx, HttpRequest req, String path) {
        String host = req.headers().get(HttpHeaderNames.HOST);
        if (host == null) {
            InetSocketAddress addr = (InetSocketAddress) ctx.channel().localAddress();
            host = addr.getHostString() + ":" + addr.getPort();
        }
        String scheme = ctx.pipeline().get(SslHandler.class) != null ? "wss" : "ws";
        return scheme + "://" + host + path;
    }

    /**
     * Netty 请求头 → Spring {@link HttpHeaders}。
     * <p>
     * 不用「继承 HttpHeaders + 自建 Map」的适配器：Spring 的契约是大小写不敏感（内部 {@code LinkedCaseInsensitiveMap}），业务按小写查
     * {@code sec-websocket-protocol} / {@code origin} 是官方示例的常规写法， 自建 {@code LinkedHashMap} 会让这类查询直接返回 null；而且父类的
     * {@code add}/{@code setAll} 写父类私有 Map， 与子类容器不一致会静默丢数据。直接构造标准 {@code HttpHeaders} 两个问题都不存在。
     * </p>
     */
    static HttpHeaders toSpringHeaders(io.netty.handler.codec.http.HttpHeaders nettyHeaders) {
        HttpHeaders headers = new HttpHeaders();
        for (Map.Entry<String, String> entry : nettyHeaders) {
            headers.add(entry.getKey(), entry.getValue());
        }
        return headers;
    }

    private static URI buildSessionUri(HttpRequest req) {
        try {
            return URI.create(req.uri());
        } catch (Exception e) {
            return URI.create("/");
        }
    }
}
