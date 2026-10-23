package io.springperf.web.websocket.server;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.ReferenceCountUtil;
import io.springperf.web.websocket.WebSocketHandlerRegistration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.WebSocketHandler;

import java.util.List;

/**
 * WebSocket 升级阶段的准入策略：per-path 配置解析、Origin 判定、拒绝响应。
 * <p>
 * 从 {@link WebSocketRoutingHandler} 拆出：这些判定只依赖注册配置与请求头，不碰 channel/pipeline 生命周期，是握手流程里唯一能脱离 Netty 事件循环单独验证的部分。
 * </p>
 * <p>
 * 不可变：构造时固定全局默认值，之后只读；每个 {@link WebSocketRoutingHandler} 实例持有一个（handler 是 {@code @Sharable}，故该策略实际被所有连接共享）。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.8
 */
@Slf4j
final class WebSocketUpgradePolicy {

    private final String subProtocols;
    private final boolean allowExtensions;
    @Nullable
    private final List<String> allowedOrigins;
    private final long idleTimeout;
    private final long heartbeatInterval;

    WebSocketUpgradePolicy(String subProtocols, boolean allowExtensions, @Nullable List<String> allowedOrigins,
            long idleTimeout, long heartbeatInterval) {
        this.subProtocols = subProtocols;
        this.allowExtensions = allowExtensions;
        this.allowedOrigins = allowedOrigins;
        this.idleTimeout = idleTimeout;
        this.heartbeatInterval = heartbeatInterval;
    }

    // ---- per-path 配置解析（有 per-path 覆盖则使用，否则回退全局） ----

    @Nullable
    List<String> resolveOrigins(@Nullable WebSocketHandlerRegistration reg) {
        if (reg != null && reg.getAllowedOrigins() != null)
            return reg.getAllowedOrigins();
        return allowedOrigins;
    }

    String resolveSubProtocols(@Nullable WebSocketHandlerRegistration reg) {
        if (reg != null && reg.getSubProtocols() != null)
            return reg.getSubProtocols();
        return subProtocols;
    }

    boolean resolveAllowExtensions(@Nullable WebSocketHandlerRegistration reg) {
        if (reg != null && reg.getAllowExtensions() != null)
            return reg.getAllowExtensions();
        return allowExtensions;
    }

    long resolveIdleTimeout(@Nullable WebSocketHandlerRegistration reg) {
        if (reg != null && reg.getIdleTimeout() != null)
            return reg.getIdleTimeout();
        return idleTimeout;
    }

    long resolveHeartbeatInterval(@Nullable WebSocketHandlerRegistration reg) {
        if (reg != null && reg.getHeartbeatInterval() != null)
            return reg.getHeartbeatInterval();
        return heartbeatInterval;
    }

    long resolveMessageSizeLimit(@Nullable WebSocketHandlerRegistration reg) {
        if (reg != null && reg.getMessageSizeLimit() != null)
            return reg.getMessageSizeLimit();
        return -1;
    }

    /**
     * 解码器的最大帧载荷长度：per-path 覆盖优先，否则取会话默认值中较大的那个（text 8KB / binary 64KB）。
     * <p>
     * 单帧长度上限只是一个技术上限；真正的 text/binary 语义上限仍由 {@code WebSocketFrameDispatcher} 按消息类型分别校验。
     * </p>
     */
    int resolveMaxFrameSize(@Nullable WebSocketHandlerRegistration reg) {
        long limit = resolveMessageSizeLimit(reg);
        if (limit > 0) {
            return (int) limit;
        }
        return Math.max(NettyWebSocketSession.DEFAULT_TEXT_MESSAGE_SIZE_LIMIT,
                NettyWebSocketSession.DEFAULT_BINARY_MESSAGE_SIZE_LIMIT);
    }

    // ---- Origin 准入 ----

    /**
     * 校验 WebSocket 握手请求的 Origin 头。
     * <p>
     * 未配置 allowedOrigins（null）时跳过校验（兼容非浏览器客户端）。显式配置为空列表时拒绝所有带 Origin 头的跨域请求。配置 {@code "*"} 则放行任意来源。
     * </p>
     *
     * @param reg
     *            per-path 注册配置；其 {@code allowedOrigins} 优先于全局默认
     */
    boolean checkOrigin(HttpRequest req, @Nullable WebSocketHandlerRegistration reg) {
        List<String> origins = resolveOrigins(reg);
        if (origins == null) {
            return true; // 未配置，允许所有来源
        }
        if (origins.isEmpty()) {
            // 显式配置为空列表 = 拒绝所有跨域请求（仅允许无 Origin 头的同源/非浏览器客户端）
            return req.headers().get(HttpHeaderNames.ORIGIN) == null;
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

    /**
     * handler 级 Origin 校验：handler 实现 {@link WebSocketOriginValidator} 时叠加一次判定。
     */
    static boolean checkHandlerOrigin(WebSocketHandler handler, @Nullable String origin) {
        if (!(handler instanceof WebSocketOriginValidator)) {
            return true;
        }
        try {
            if (((WebSocketOriginValidator) handler).isOriginAllowed(origin)) {
                return true;
            }
        } catch (RuntimeException ex) {
            // 用户实现的判定（如 Configurator#checkOrigin）抛异常时，若放异常逃逸，连接既不会升级也不会收到响应，
            // 客户端只能等到超时；这里按「拒绝」处理并留下上下文。
            log.error("WebSocket connection rejected: origin check by handler threw, origin '{}'", origin, ex);
            return false;
        }
        log.warn("WebSocket connection rejected: origin '{}' rejected by handler", origin);
        return false;
    }

    /** 拒绝升级：回 403 并关闭连接，同时释放请求引用（拒绝路径不透传，也不由握手器接管）。 */
    static void sendForbiddenResponse(ChannelHandlerContext ctx, HttpRequest req) {
        DefaultHttpResponse resp = new DefaultHttpResponse(req.protocolVersion(), HttpResponseStatus.FORBIDDEN);
        if (HttpUtil.isKeepAlive(req)) {
            resp.headers().set(HttpHeaderNames.CONNECTION, "close");
        }
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        // 拒绝路径不将请求透传下游，释放 FullHttpRequest 引用（含 content ByteBuf）。
        // （握手成功路径由 WebSocketServerHandshaker.handshake 接管并释放，此处只处理拒绝路径。）
        ReferenceCountUtil.release(req);
    }
}
