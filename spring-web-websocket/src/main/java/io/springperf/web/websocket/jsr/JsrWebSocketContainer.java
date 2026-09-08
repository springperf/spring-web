package io.springperf.web.websocket.jsr;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Set;

/**
 * JSR-356 {@link WebSocketContainer} 鐨勬湇鍔＄瀹炵幇銆?
 *
 * <p>鏈鏋朵娇鐢?Netty 鐩存帴澶勭悊 WebSocket锛屾棤闇€鐪熷疄鐨?Servlet 瀹瑰櫒锛屽洜姝わ細
 * 瀹㈡埛绔繛鎺ユ柟娉曪紙{@code connectToServer}锛変笉鍙楁敮鎸侊紱浠呮彁渚涢粯璁や細璇濊秴鏃?缂撳啿鍖洪厤缃紝
 * 渚?{@link JsrWebSocketSession} 鏌ヨ銆?/p>
 *
 * @author huangcanda
 * @since 3.2.5
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
