package io.springperf.web.websocket;

import org.springframework.web.socket.WebSocketHandler;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSocket 处理器注册中心，收集路径到 {@link WebSocketHandler} 的映射。
 *
 * @author huangcanda
 * @since 1.0.4
 */
public class WebSocketHandlerRegistry {

    private final Map<String, WebSocketHandlerRegistration> registrations = new LinkedHashMap<>();

    private volatile List<String> allowedOrigins;
    private long idleTimeout = -1;
    private String subProtocols;
    private boolean allowExtensions;
    private long heartbeatInterval = -1;

    /**
     * 注册 WebSocket 处理器到指定路径。
     */
    public WebSocketHandlerRegistration addHandler(WebSocketHandler handler, String... paths) {
        WebSocketHandlerRegistration reg = new WebSocketHandlerRegistration(handler, paths);
        for (String path : paths) {
            if (registrations.containsKey(path)) {
                throw new IllegalArgumentException(
                        "WebSocket handler already registered for path: " + path);
            }
            registrations.put(path, reg);
        }
        return reg;
    }

    /**
     * 根据路径查找对应的处理器注册信息。
     */
    public WebSocketHandlerRegistration getRegistration(String path) {
        // 1. 精确匹配
        WebSocketHandlerRegistration reg = registrations.get(path);
        if (reg != null) return reg;

        // 2. RouteMatcher 模式匹配
        org.springframework.util.RouteMatcher routeMatcher =
                io.springperf.web.util.PathPatternUtils.getPatternRouteMatcher();
        org.springframework.util.RouteMatcher.Route route = routeMatcher.parseRoute(path);
        for (Map.Entry<String, WebSocketHandlerRegistration> entry : registrations.entrySet()) {
            if (routeMatcher.match(entry.getKey(), route)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 设置允许的 Origin 来源列表，用于 WebSocket 握手请求的 {@code Origin} 头校验。
     * <p>不调用此方法时（null）不做校验，允许所有来源。
     * 显式传入空列表时拒绝所有带 Origin 头的跨域请求（仅允许同源和没有 Origin 头的非浏览器客户端）。
     * 设置具体来源后浏览器发起跨域连接时将被校验。</p>
     */
    public WebSocketHandlerRegistry setAllowedOrigins(String... origins) {
        this.allowedOrigins = origins != null ? Arrays.asList(origins) : null;
        return this;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    /**
     * 设置 WebSocket 连接空闲超时（毫秒），超过此时间无读写则自动关闭。
     * 默认 -1 表示不启用空闲检测。
     */
    public WebSocketHandlerRegistry setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout;
        return this;
    }

    public long getIdleTimeout() {
        return idleTimeout;
    }

    /**
     * 设置 WebSocket 子协议协商字符串，多个协议用逗号分隔。
     * 对应 Sec-WebSocket-Protocol 响应头。
     */
    public WebSocketHandlerRegistry setSubProtocols(String subProtocols) {
        this.subProtocols = subProtocols;
        return this;
    }

    public String getSubProtocols() {
        return subProtocols;
    }

    /**
     * 设置是否允许 WebSocket 扩展协商（如 permessage-deflate 压缩）。
     * 默认 false。
     */
    public WebSocketHandlerRegistry setAllowExtensions(boolean allowExtensions) {
        this.allowExtensions = allowExtensions;
        return this;
    }

    public boolean isAllowExtensions() {
        return allowExtensions;
    }

    /**
     * 设置 WebSocket 主动心跳间隔（毫秒），默认 -1 不启用。
     * 启用后服务器定时发送 Ping 帧保活，防止 NAT/负载均衡器断连。
     */
    public WebSocketHandlerRegistry setHeartbeatInterval(long heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
        return this;
    }

    public long getHeartbeatInterval() {
        return heartbeatInterval;
    }

    /**
     * 返回路径 → handler 的映射（兼容旧版，用于构建简单路由）。
     */
    public Map<String, WebSocketHandler> getHandlerMap() {
        Map<String, WebSocketHandler> map = new LinkedHashMap<>();
        for (Map.Entry<String, WebSocketHandlerRegistration> entry : registrations.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getHandler());
        }
        return map;
    }

    public boolean isEmpty() {
        return registrations.isEmpty();
    }
}
