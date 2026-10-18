package io.springperf.web.websocket;

import org.springframework.web.socket.WebSocketHandler;

import java.util.Arrays;
import java.util.List;

/**
 * 单条 WebSocket 处理器注册的配置持有者。 支持 per-handler 覆盖全局配置。
 *
 * @author huangcanda
 *
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
        // 返回副本；paths 为 null 时保持原有返回 null 的行为
        return paths == null ? null : paths.clone();
    }

    public WebSocketHandlerRegistration setAllowedOrigins(String... origins) {
        // Arrays.asList 只是包装（不是拷贝）：调用方改数组会改到这里的配置，故先克隆
        this.allowedOrigins = origins != null ? Arrays.asList(origins.clone()) : null;
        return this;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins == null ? null : java.util.Collections.unmodifiableList(allowedOrigins);
    }

    public WebSocketHandlerRegistration setSubProtocols(String subProtocols) {
        this.subProtocols = subProtocols;
        return this;
    }

    public String getSubProtocols() {
        return subProtocols;
    }

    /**
     * 是否允许协商 WebSocket 扩展。目前仅支持 {@code permessage-deflate}：开启后服务端在握手时与客户端协商， 协商成功则双向压缩（控制帧不压缩）；客户端未提扩展则不启用压缩，此时带 RSV
     * 位的帧会被解码器拒绝， 而不是当作普通数据交给业务。
     *
     * @param allowExtensions
     *            是否参与扩展协商
     */
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
     * 设置该路径 WebSocket 连接允许的最大帧载荷长度（字节），覆盖 {@link io.springperf.web.websocket.server.NettyWebSocketSession} 默认的 text 8KB
     * / binary 64KB，用于按路径差异化帧上限（如大文件通道 vs 心跳通道）（L14）。
     *
     * @param messageSizeLimit
     *            单帧最大字节数，需 &gt; 0
     */
    public WebSocketHandlerRegistration setMessageSizeLimit(long messageSizeLimit) {
        this.messageSizeLimit = messageSizeLimit;
        return this;
    }

    public Long getMessageSizeLimit() {
        return messageSizeLimit;
    }
}
