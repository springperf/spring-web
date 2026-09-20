package io.springperf.web.websocket;

import org.springframework.web.socket.WebSocketHandler;

import java.util.Arrays;
import java.util.List;

/**
 * 单条 WebSocket 处理器注册的配置持有者。
 * 支持 per-handler 覆盖全局配置。
 *
 * @author huangcanda
 * @since 1.0.4
 */
public class WebSocketHandlerRegistration {

    private final WebSocketHandler handler;
    private final String[] paths;

    private List<String> allowedOrigins;
    private String subProtocols;
    private Boolean allowExtensions;
    private Long idleTimeout;
    private Long heartbeatInterval;
    private Long messageSizeLimit;

    public WebSocketHandlerRegistration(WebSocketHandler handler, String... paths) {
        this.handler = handler;
        this.paths = paths;
    }

    public WebSocketHandler getHandler() {
        return handler;
    }

    public String[] getPaths() {
        return paths;
    }

    public WebSocketHandlerRegistration setAllowedOrigins(String... origins) {
        this.allowedOrigins = origins != null ? Arrays.asList(origins) : null;
        return this;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public WebSocketHandlerRegistration setSubProtocols(String subProtocols) {
        this.subProtocols = subProtocols;
        return this;
    }

    public String getSubProtocols() {
        return subProtocols;
    }

    public WebSocketHandlerRegistration setAllowExtensions(boolean allowExtensions) {
        this.allowExtensions = allowExtensions;
        return this;
    }

    public Boolean getAllowExtensions() {
        return allowExtensions;
    }

    public WebSocketHandlerRegistration setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout;
        return this;
    }

    public Long getIdleTimeout() {
        return idleTimeout;
    }

    public WebSocketHandlerRegistration setHeartbeatInterval(long heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
        return this;
    }

    public Long getHeartbeatInterval() {
        return heartbeatInterval;
    }

    /**
     * 设置该路径 WebSocket 连接允许的最大帧载荷长度（字节），覆盖 {@link io.springperf.web.websocket.server.NettyWebSocketSession}
     * 默认的 text 8KB / binary 64KB，用于按路径差异化帧上限（如大文件通道 vs 心跳通道）（L14）。
     *
     * @param messageSizeLimit 单帧最大字节数，需 &gt; 0
     */
    public WebSocketHandlerRegistration setMessageSizeLimit(long messageSizeLimit) {
        this.messageSizeLimit = messageSizeLimit;
        return this;
    }

    public Long getMessageSizeLimit() {
        return messageSizeLimit;
    }
}
