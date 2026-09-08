package io.springperf.web.websocket.jsr;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.Endpoint;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class JsrWebSocketContainerTest {

    @Test
    void asyncSendTimeout_getSet() {
        JsrWebSocketContainer container = new JsrWebSocketContainer();
        assertEquals(-1L, container.getDefaultAsyncSendTimeout());
        container.setAsyncSendTimeout(5000);
        assertEquals(5000L, container.getDefaultAsyncSendTimeout());
    }

    @Test
    void maxSessionIdleTimeout_getSet() {
        JsrWebSocketContainer container = new JsrWebSocketContainer();
        assertEquals(-1L, container.getDefaultMaxSessionIdleTimeout());
        container.setDefaultMaxSessionIdleTimeout(60000);
        assertEquals(60000L, container.getDefaultMaxSessionIdleTimeout());
    }

    @Test
    void binaryMessageBufferSize_getSet() {
        JsrWebSocketContainer container = new JsrWebSocketContainer();
        assertEquals(64 * 1024, container.getDefaultMaxBinaryMessageBufferSize());
        container.setDefaultMaxBinaryMessageBufferSize(128 * 1024);
        assertEquals(128 * 1024, container.getDefaultMaxBinaryMessageBufferSize());
    }

    @Test
    void textMessageBufferSize_getSet() {
        JsrWebSocketContainer container = new JsrWebSocketContainer();
        assertEquals(8 * 1024, container.getDefaultMaxTextMessageBufferSize());
        container.setDefaultMaxTextMessageBufferSize(16 * 1024);
        assertEquals(16 * 1024, container.getDefaultMaxTextMessageBufferSize());
    }

    @Test
    void installedExtensions_empty() {
        JsrWebSocketContainer container = new JsrWebSocketContainer();
        assertTrue(container.getInstalledExtensions().isEmpty());
    }

    @Test
    void connectToServer_unsupported() {
        JsrWebSocketContainer container = new JsrWebSocketContainer();
        assertThrows(UnsupportedOperationException.class,
                () -> container.connectToServer((Object) null, URI.create("ws://x")));
        assertThrows(UnsupportedOperationException.class,
                () -> container.connectToServer((Class<?>) null, URI.create("ws://x")));
        assertThrows(UnsupportedOperationException.class,
                () -> container.connectToServer((Endpoint) null, (ClientEndpointConfig) null, URI.create("ws://x")));
        assertThrows(UnsupportedOperationException.class,
                () -> container.connectToServer((Class<? extends Endpoint>) null, (ClientEndpointConfig) null, URI.create("ws://x")));
    }
}
