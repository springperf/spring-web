package io.springperf.web.websocket.jsr;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Extension;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSR-356 {@link Session} 实现，包装底层 Spring {@link WebSocketSession}（Netty 管道）。
 *
 * <p>职责映射：</p>
 * <ul>
 *   <li>发送 → 委托 {@link WebSocketSession#sendMessage}（文本/二进制/Ping/Pong）</li>
 *   <li>关闭 → 委托 {@link WebSocketSession#close(CloseStatus)}</li>
 *   <li>路径变量 → 握手阶段由 RouteMatcher 提取后注入</li>
 *   <li>{@code addMessageHandler} → 首期不支持（由 {@code @OnMessage} 注解驱动）</li>
 * </ul>
 *
 * @author huangcanda
 * @since 3.5.6
 */
public class JsrWebSocketSession implements Session {

    private final WebSocketSession springSession;
    private final JsrWebSocketContainer container;
    private final JsrCodecRegistry codecRegistry;
    private final Map<String, String> pathParameters;
    private final Map<String, Object> userProperties = new ConcurrentHashMap<>();

    private final JsrRemoteEndpointBasic basicRemote;
    private final JsrRemoteEndpointAsync asyncRemote;

    private long maxIdleTimeout = -1;

    public JsrWebSocketSession(WebSocketSession springSession,
                               JsrWebSocketContainer container,
                               JsrCodecRegistry codecRegistry,
                               Map<String, String> pathParameters) {
        this.springSession = springSession;
        this.container = container;
        this.codecRegistry = codecRegistry;
        this.pathParameters = new HashMap<>(pathParameters);
        this.basicRemote = new JsrRemoteEndpointBasic(this, codecRegistry);
        this.asyncRemote = new JsrRemoteEndpointAsync(this, codecRegistry);
    }

    public WebSocketSession getSpringSession() {
        return springSession;
    }

    @Override
    public WebSocketContainer getContainer() {
        return container;
    }

    @Override
    public void addMessageHandler(MessageHandler handler) throws IllegalStateException {
        throw new UnsupportedOperationException(
                "Dynamic MessageHandler registration is not supported; use @OnMessage annotation");
    }

    @Override
    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Whole<T> handler) {
        throw new UnsupportedOperationException(
                "Dynamic MessageHandler registration is not supported; use @OnMessage annotation");
    }

    @Override
    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Partial<T> handler) {
        throw new UnsupportedOperationException(
                "Dynamic MessageHandler registration is not supported; use @OnMessage annotation");
    }

    @Override
    public Set<MessageHandler> getMessageHandlers() {
        return Collections.emptySet();
    }

    @Override
    public void removeMessageHandler(MessageHandler handler) {
        // 无动态注册，无操作
    }

    @Override
    public String getProtocolVersion() {
        return "13";
    }

    @Override
    public String getNegotiatedSubprotocol() {
        return springSession.getAcceptedProtocol();
    }

    @Override
    public List<Extension> getNegotiatedExtensions() {
        return Collections.emptyList();
    }

    @Override
    public boolean isSecure() {
        return "wss".equalsIgnoreCase(springSession.getUri().getScheme());
    }

    @Override
    public boolean isOpen() {
        return springSession.isOpen();
    }

    @Override
    public long getMaxIdleTimeout() {
        return maxIdleTimeout;
    }

    @Override
    public void setMaxIdleTimeout(long milliseconds) {
        this.maxIdleTimeout = milliseconds;
    }

    @Override
    public void setMaxBinaryMessageBufferSize(int max) {
        springSession.setBinaryMessageSizeLimit(max);
    }

    @Override
    public int getMaxBinaryMessageBufferSize() {
        return springSession.getBinaryMessageSizeLimit();
    }

    @Override
    public void setMaxTextMessageBufferSize(int max) {
        springSession.setTextMessageSizeLimit(max);
    }

    @Override
    public int getMaxTextMessageBufferSize() {
        return springSession.getTextMessageSizeLimit();
    }

    @Override
    public RemoteEndpoint.Async getAsyncRemote() {
        return asyncRemote;
    }

    @Override
    public RemoteEndpoint.Basic getBasicRemote() {
        return basicRemote;
    }

    @Override
    public String getId() {
        return springSession.getId();
    }

    @Override
    public void close() throws IOException {
        springSession.close();
    }

    @Override
    public void close(CloseReason closeStatus) throws IOException {
        CloseStatus status = new CloseStatus(closeStatus.getCloseCode().getCode(), closeStatus.getReasonPhrase());
        springSession.close(status);
    }

    @Override
    public URI getRequestURI() {
        return springSession.getUri();
    }

    @Override
    public Map<String, List<String>> getRequestParameterMap() {
        String query = getQueryString();
        if (query == null || query.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            params.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(value);
        }
        return params;
    }

    @Override
    public String getQueryString() {
        return springSession.getUri().getRawQuery();
    }

    @Override
    public Map<String, String> getPathParameters() {
        return pathParameters;
    }

    @Override
    public Map<String, Object> getUserProperties() {
        return userProperties;
    }

    @Override
    public Principal getUserPrincipal() {
        return springSession.getPrincipal();
    }

    @Override
    public Set<Session> getOpenSessions() {
        return Collections.emptySet();
    }

    JsrCodecRegistry getCodecRegistry() {
        return codecRegistry;
    }
}
