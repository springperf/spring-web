package io.springperf.web.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketHandlerRegistrationTest {

    private final WebSocketHandler handler = new TextWebSocketHandler() {};

    @Test
    void constructor_storesHandlerAndPaths() {
        WebSocketHandlerRegistration reg = new WebSocketHandlerRegistration(handler, "/ws", "/chat");
        assertSame(handler, reg.getHandler());
        assertArrayEquals(new String[]{"/ws", "/chat"}, reg.getPaths());
    }

    @Test
    void setAllowedOrigins_storesAndReturnsSelf() {
        WebSocketHandlerRegistration reg = new WebSocketHandlerRegistration(handler, "/ws");
        WebSocketHandlerRegistration result = reg.setAllowedOrigins("http://a.com", "http://b.com");
        assertSame(reg, result);
        assertEquals(Arrays.asList("http://a.com", "http://b.com"), reg.getAllowedOrigins());
    }

    @Test
    void setAllowedOrigins_null_clears() {
        WebSocketHandlerRegistration reg = new WebSocketHandlerRegistration(handler, "/ws");
        reg.setAllowedOrigins("http://a.com");
        reg.setAllowedOrigins((String[]) null);
        assertNull(reg.getAllowedOrigins());
    }

    @Test
    void setSubProtocols_stores() {
        WebSocketHandlerRegistration reg = new WebSocketHandlerRegistration(handler, "/ws");
        reg.setSubProtocols("chat,superchat");
        assertEquals("chat,superchat", reg.getSubProtocols());
    }

    @Test
    void setAllowExtensions_stores() {
        WebSocketHandlerRegistration reg = new WebSocketHandlerRegistration(handler, "/ws");
        reg.setAllowExtensions(true);
        assertTrue(reg.getAllowExtensions());
    }

    @Test
    void setIdleTimeout_stores() {
        WebSocketHandlerRegistration reg = new WebSocketHandlerRegistration(handler, "/ws");
        reg.setIdleTimeout(30000L);
        assertEquals(Long.valueOf(30000), reg.getIdleTimeout());
    }

    @Test
    void setHeartbeatInterval_stores() {
        WebSocketHandlerRegistration reg = new WebSocketHandlerRegistration(handler, "/ws");
        reg.setHeartbeatInterval(25000L);
        assertEquals(Long.valueOf(25000), reg.getHeartbeatInterval());
    }

    @Test
    void defaults_areNull() {
        WebSocketHandlerRegistration reg = new WebSocketHandlerRegistration(handler, "/ws");
        assertNull(reg.getAllowedOrigins());
        assertNull(reg.getSubProtocols());
        assertNull(reg.getAllowExtensions());
        assertNull(reg.getIdleTimeout());
        assertNull(reg.getHeartbeatInterval());
    }
}
