package io.springperf.web.websocket.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import io.springperf.web.util.PathPatternUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.util.RouteMatcher;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Netty pipeline handler：拦截 HTTP WebSocket 升级请求并委托 Spring。
 * <p>
 * 放置在 {@code HttpObjectAggregator} 之前，拦截带 {@code Upgrade: websocket} 头的 HTTP 请求。匹配到已注册路径后：
 * </p>
 * <ol>
 * <li>路由：按精确路径 / 路径模式找到 {@link WebSocketHandler} 及其注册配置</li>
 * <li>握手：交给 {@link WebSocketHandshakeHandler} 完成 Origin 准入、升级、pipeline 切换与会话建立</li>
 * <li>后续 WebSocket 帧交给 {@link WebSocketFrameDispatcher} 按 RFC 6455 语义分发</li>
 * <li>连接生命周期（空闲、异常、断开）由本类回调到 handler</li>
 * </ol>
 * <p>
 * 非 WebSocket 升级请求透传，不影响正常 HTTP 处理。
 * </p>
 *
 * @author huangcanda
 *
 * @since 1.0.4
 */
@Slf4j
@ChannelHandler.Sharable
public class WebSocketRoutingHandler extends ChannelInboundHandlerAdapter {

    /**
     * 单连接聚合状态：会话、handler、分片状态收进一个对象，每帧只做 1 次 attribute 查询。
     * <p>
     * 修复前每帧要查 3~4 次 channel attribute（session / handler / fragment 各一个 key）。 这些字段只在 EventLoop 线程读写，故无需同步。
     * </p>
     */
    static final AttributeKey<WsConnection> CONNECTION_KEY = AttributeKey.valueOf("ws.connection");

    /** 单连接聚合状态。字段只在 EventLoop 线程读写。 */
    static final class WsConnection {
        final NettyWebSocketSession session;
        final WebSocketHandler handler;
        /** 进行中的分片消息聚合状态；无分片时为 null（由 {@link WebSocketFrameDispatcher} 维护）。 */
        WebSocketFrameDispatcher.FragmentState fragment;

        WsConnection(NettyWebSocketSession session, WebSocketHandler handler) {
            this.session = session;
            this.handler = handler;
        }
    }

    private final Map<String, WebSocketHandler> handlerMap;
    private final WebSocketHandshakeHandler handshakeHandler;
    @Nullable
    private final io.springperf.web.websocket.WebSocketHandlerRegistry registry;

    public WebSocketRoutingHandler(Map<String, WebSocketHandler> handlerMap) {
        this(handlerMap, null, false, null, -1, -1, null);
    }

    public WebSocketRoutingHandler(Map<String, WebSocketHandler> handlerMap, String subProtocols,
            boolean allowExtensions) {
        this(handlerMap, subProtocols, allowExtensions, null, -1, -1, null);
    }

    public WebSocketRoutingHandler(Map<String, WebSocketHandler> handlerMap, String subProtocols,
            boolean allowExtensions, List<String> allowedOrigins) {
        this(handlerMap, subProtocols, allowExtensions, allowedOrigins, -1, -1, null);
    }

    public WebSocketRoutingHandler(Map<String, WebSocketHandler> handlerMap, String subProtocols,
            boolean allowExtensions, List<String> allowedOrigins, long idleTimeout) {
        this(handlerMap, subProtocols, allowExtensions, allowedOrigins, idleTimeout, -1, null);
    }

    public WebSocketRoutingHandler(Map<String, WebSocketHandler> handlerMap, String subProtocols,
            boolean allowExtensions, List<String> allowedOrigins, long idleTimeout, long heartbeatInterval) {
        this(handlerMap, subProtocols, allowExtensions, allowedOrigins, idleTimeout, heartbeatInterval, null);
    }

    public WebSocketRoutingHandler(Map<String, WebSocketHandler> handlerMap, String subProtocols,
            boolean allowExtensions, List<String> allowedOrigins, long idleTimeout, long heartbeatInterval,
            @Nullable io.springperf.web.websocket.WebSocketHandlerRegistry registry) {
        this.handlerMap = handlerMap;
        // 全局默认值（子协议 / 扩展 / 白名单 / 空闲 / 心跳）随握手器一起下沉，路由侧不再持有
        this.handshakeHandler = new WebSocketHandshakeHandler(subProtocols, allowExtensions, allowedOrigins,
                idleTimeout, heartbeatInterval);
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
            // 帧语义（分片聚合、size limit、控制帧、Close 语义）全部委托给 WebSocketFrameDispatcher
            WebSocketFrameDispatcher.dispatch(ctx, (WebSocketFrame) msg);
            return;
        }
        ctx.fireChannelRead(msg);
    }

    private void handleWebSocketUpgrade(ChannelHandlerContext ctx, HttpRequest req) {
        String path = new QueryStringDecoder(req.uri()).path();
        WebSocketPathMatch match = resolveHandler(path);
        if (match == null) {
            ctx.fireChannelRead(req);
            return;
        }
        handshakeHandler.upgrade(ctx, req, match);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        WsConnection connection = ctx.channel().attr(CONNECTION_KEY).get();
        if (connection != null) {
            try {
                connection.handler.handleTransportError(connection.session, cause);
            } catch (Exception e) {
                log.error("TransportError handler failed", e);
            }
        }
        ctx.close();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable()) {
            WsBackpressureQueue.drain(ctx.channel());
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            WsConnection connection = ctx.channel().attr(CONNECTION_KEY).get();
            if (connection != null) {
                WebSocketHandler handler = connection.handler;
                NettyWebSocketSession session = connection.session;
                log.warn("WebSocket idle timeout, closing session {}", session.getId());
                try {
                    handler.handleTransportError(session, new IOException("Connection idle timeout"));
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
        WsConnection connection = ctx.channel().attr(CONNECTION_KEY).get();
        // 幂等通知：Netty 保证 channelInactive 触发时 channel 已 inactive，故不能用 isOpen()
        // （= open && channel.isActive()）做守卫——那样恒为 false，除「对端发 Close 帧」外的
        // 所有断连路径（TCP 断开/RST、服务端 close()、空闲超时、传输异常）都会丢失回调。
        // markCloseNotified 用 CAS 保证四条路径竞争下恰好通知一次。
        if (connection != null) {
            NettyWebSocketSession session = connection.session;
            if (session.markCloseNotified()) {
                session.setOpen(false);
                try {
                    connection.handler.afterConnectionClosed(session, CloseStatus.GOING_AWAY);
                } catch (Exception e) {
                    log.error("afterConnectionClosed failed", e);
                }
            }
        }
        // 无论是否已通知过关闭，都要丢弃进行中的分片缓冲：连接已断，聚合永不完成
        WebSocketFrameDispatcher.releaseFragment(connection);
        ctx.fireChannelInactive();
    }

    /**
     * 取回握手后挂在 channel 上的会话（{@code WebSocketRoutingHandler} 的包内诊断入口，也供测试驱动主动 close）。
     *
     * @return 会话；未完成握手或非 WebSocket 连接时为 null
     */
    @Nullable
    static NettyWebSocketSession sessionOf(Channel channel) {
        WsConnection connection = channel.attr(CONNECTION_KEY).get();
        return connection != null ? connection.session : null;
    }

    /**
     * 解析请求路径：先精确匹配（快速路径，无路径变量），再走 {@link RouteMatcher} 模式匹配（支持 {@code /**} 通配、{@code {roomId}}）。
     *
     * @return 命中结果；无匹配时返回 null（调用方透传给下游 HTTP 处理）
     */
    @Nullable
    private WebSocketPathMatch resolveHandler(String path) {
        // 1. 精确匹配（快速路径，无路径变量）
        WebSocketHandler handler = handlerMap.get(path);
        if (handler != null) {
            return new WebSocketPathMatch(path, handler, registrationOf(path), Collections.emptyMap());
        }

        // 2. RouteMatcher 模式匹配（支持 /** 通配、{roomId} 路径变量等）
        RouteMatcher routeMatcher = PathPatternUtils.getPatternRouteMatcher();
        RouteMatcher.Route route = routeMatcher.parseRoute(path);
        for (Map.Entry<String, WebSocketHandler> entry : handlerMap.entrySet()) {
            Map<String, String> variables = routeMatcher.matchAndExtract(entry.getKey(), route);
            if (variables != null) {
                return new WebSocketPathMatch(path, entry.getValue(), registrationOf(entry.getKey()), variables);
            }
        }

        return null;
    }

    /** per-path 注册配置；未接入 registry（如直接构造 handlerMap）时为 null，握手侧按全局默认处理。 */
    @Nullable
    private io.springperf.web.websocket.WebSocketHandlerRegistration registrationOf(String path) {
        return registry != null ? registry.getRegistration(path) : null;
    }
}
