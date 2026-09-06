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
import java.util.Set;

/**
 * JSR-356 {@link WebSocketContainer} 的服务端实现。
 *
 * <p>本框架使用 Netty 直接处理 WebSocket，无需真实的 Servlet 容器，因此：
 * 客户端连接方法（{@code connectToServer}）不受支持；仅提供默认会话超时/缓冲区配置，
 * 供 {@link JsrWebSocketSession} 查询。</p>
 *
 * @author huangcanda
 * @since 3.5.6
 */
public class JsrWebSocketContainer implements WebSocketContainer {

    private long defaultAsyncSendTimeout = -1;
    private long defaultMaxSessionIdleTimeout = -1;
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
    public Session connectToServer(Object annotatedEndpointInstance, URI path)
            throws DeploymentException, IOException {
        throw new UnsupportedOperationException("connectToServer is not supported (server-side container)");
    }

    @Override
    public Session connectToServer(Class<?> annotatedEndpointClass, URI path)
            throws DeploymentException, IOException {
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
}
