package io.springperf.web.websocket.jsr;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Extension;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSR-356 {@link WebSocketContainer} 的服务端实现。
 * <p>
 * 本框架使用 Netty 直接处理 WebSocket，无需真实的 Servlet 容器，因此： 客户端连接方法（{@code connectToServer}）不受支持；仅提供默认会话超时/缓冲区配置， 供
 * {@link JsrWebSocketSession} 查询。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.6
 */
public class JsrWebSocketContainer implements WebSocketContainer {

    private long defaultAsyncSendTimeout = -1;
    private long defaultMaxSessionIdleTimeout = -1;
    /** endpoint 路径 → 该端点的在线会话（{@code Session#getOpenSessions()} 的数据源）。 */
    private final Map<String, Set<JsrWebSocketSession>> openSessions = new ConcurrentHashMap<>();
    private int defaultMaxBinaryMessageBufferSize = 64 * 1024;
    private int defaultMaxTextMessageBufferSize = 8 * 1024;

    @Override
    public long getDefaultAsyncSendTimeout() {
        return defaultAsyncSendTimeout;
    }

    @Override
    public void setAsyncSendTimeout(long timeout) {
        this.defaultAsyncSendTimeout = timeout;
    }

    @Override
    public Session connectToServer(Object annotatedEndpointInstance, URI path) throws DeploymentException, IOException {
        throw new UnsupportedOperationException("connectToServer is not supported (server-side container)");
    }

    @Override
    public Session connectToServer(Class<?> annotatedEndpointClass, URI path) throws DeploymentException, IOException {
        throw new UnsupportedOperationException("connectToServer is not supported (server-side container)");
    }

    @Override
    public Session connectToServer(Endpoint endpointInstance, ClientEndpointConfig config, URI path)
            throws DeploymentException, IOException {
        throw new UnsupportedOperationException("connectToServer is not supported (server-side container)");
    }

    @Override
    public Session connectToServer(Class<? extends Endpoint> endpointClass, ClientEndpointConfig config, URI path)
            throws DeploymentException, IOException {
        throw new UnsupportedOperationException("connectToServer is not supported (server-side container)");
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout() {
        return defaultMaxSessionIdleTimeout;
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long timeout) {
        this.defaultMaxSessionIdleTimeout = timeout;
    }

    @Override
    public int getDefaultMaxBinaryMessageBufferSize() {
        return defaultMaxBinaryMessageBufferSize;
    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max) {
        this.defaultMaxBinaryMessageBufferSize = max;
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize() {
        return defaultMaxTextMessageBufferSize;
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max) {
        this.defaultMaxTextMessageBufferSize = max;
    }

    @Override
    public Set<Extension> getInstalledExtensions() {
        return Collections.emptySet();
    }

    /**
     * 登记一个刚建立的会话（供 {@link jakarta.websocket.Session#getOpenSessions()} 查询）。
     *
     * @param endpointPath
     *            endpoint 的声明路径（不是请求路径），作为「同一 endpoint」的判定键
     */
    void registerSession(String endpointPath, JsrWebSocketSession session) {
        openSessions.computeIfAbsent(endpointPath, key -> ConcurrentHashMap.newKeySet()).add(session);
    }

    /**
     * 注销会话；集合空了立即从表里移除，避免历史路径无限累积。
     */
    void unregisterSession(String endpointPath, JsrWebSocketSession session) {
        Set<JsrWebSocketSession> sessions = openSessions.get(endpointPath);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            openSessions.remove(endpointPath, sessions);
        }
    }

    /**
     * 某 endpoint 当前的会话快照（不可变）：JSR-356 的 {@code getOpenSessions()} 语义是同一 endpoint 的所有打开会话， 且业务常在遍历中关闭会话，故返回快照而不是活集合。
     */
    Set<Session> openSessionsOf(String endpointPath) {
        Set<JsrWebSocketSession> sessions = openSessions.get(endpointPath);
        if (sessions == null || sessions.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(sessions));
    }
}
