package io.springperf.web.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketHandlerRegistryTest {

    private final WebSocketHandler handler = new TextWebSocketHandler() {};

    @Test
    void addHandler_singlePath_registers() {
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        WebSocketHandlerRegistration reg = registry.addHandler(handler, "/ws");
        assertNotNull(reg);
        assertSame(handler, registry.getRegistration("/ws").getHandler());
        assertFalse(registry.isEmpty());
    }

    @Test
    void addHandler_multiplePaths_registersAll() {
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        registry.addHandler(handler, "/a", "/b");
        assertSame(handler, registry.getRegistration("/a").getHandler());
        assertSame(handler, registry.getRegistration("/b").getHandler());
        assertEquals(2, registry.getHandlerMap().size());
    }

    @Test
    void addHandler_duplicatePath_throws() {
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        registry.addHandler(handler, "/ws");
        assertThrows(IllegalArgumentException.class,
                () -> registry.addHandler(handler, "/ws"));
    }

    @Test
    void getRegistration_patternMatch_returnsHandler() {
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        registry.addHandler(handler, "/topic/*");
        assertSame(handler, registry.getRegistration("/topic/news").getHandler());
    }

    @Test
    void getRegistration_noMatch_returnsNull() {
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        registry.addHandler(handler, "/ws");
        assertNull(registry.getRegistration("/missing"));
    }

    @Test
    void getRegistration_empty_returnsNull() {
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        assertNull(registry.getRegistration("/ws"));
    }

    @Test
    void setAllowedOrigins_global() {
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        registry.setAllowedOrigins("http://a.com", "http://b.com");
        assertEquals(2, registry.getAllowedOrigins().size());
    }

    @Test
    void setAllowedOrigins_null_meansAllowAll() {
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        registry.setAllowedOrigins("http://a.com");
        registry.setAllowedOrigins((String[]) null);
        assertNull(registry.getAllowedOrigins());
    }

    @Test
    void setIdleTimeout_subProtocols_allowExtensions_heartbeat() {
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        WebSocketHandlerRegistry self = registry
                .setIdleTimeout(30000)
                .setSubProtocols("chat")
                .setAllowExtensions(true)
                .setHeartbeatInterval(20000);
        assertSame(registry, self);
        assertEquals(30000L, registry.getIdleTimeout());
        assertEquals("chat", registry.getSubProtocols());
        assertTrue(registry.isAllowExtensions());
        assertEquals(20000L, registry.getHeartbeatInterval());
    }

    @Test
    void defaults_areOff() {
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        assertTrue(registry.isEmpty());
        assertEquals(-1L, registry.getIdleTimeout());
        assertEquals(-1L, registry.getHeartbeatInterval());
        assertFalse(registry.isAllowExtensions());
        assertNull(registry.getAllowedOrigins());
        assertNull(registry.getSubProtocols());
    }

    @Test
    void getHandlerMap_empty_returnsEmpty() {
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        assertTrue(registry.getHandlerMap().isEmpty());
    }
}
