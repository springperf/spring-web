package io.springperf.web.websocket.jsr;

import javax.websocket.CloseReason;
import javax.websocket.MessageHandler;
import javax.websocket.RemoteEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JsrWebSocketSessionTest {

    private WebSocketSession springSession;
    private JsrWebSocketContainer container;
    private JsrCodecRegistry codecRegistry;
    private JsrWebSocketSession session;

    @BeforeEach
    void setUp() {
        springSession = mock(WebSocketSession.class);
        container = new JsrWebSocketContainer();
        codecRegistry = new JsrCodecRegistry(
                new JsrEndpointConfigAdapter(new JsrEndpointMetadata(PlainEndpoint.class)));
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("roomId", "42");
        session = new JsrWebSocketSession(springSession, container, codecRegistry, pathParams);
    }

    @javax.websocket.server.ServerEndpoint("/ws")
    static class PlainEndpoint {}

    @Test
    void getContainer_returnsContainer() {
        assertSame(container, session.getContainer());
    }

    @Test
    void addMessageHandler_unsupported() {
        assertThrows(UnsupportedOperationException.class, () -> session.addMessageHandler(mock(MessageHandler.class)));
        assertThrows(UnsupportedOperationException.class, () -> session.addMessageHandler(String.class, mock(MessageHandler.Whole.class)));
        assertThrows(UnsupportedOperationException.class, () -> session.addMessageHandler(String.class, mock(MessageHandler.Partial.class)));
    }

    @Test
    void getMessageHandlers_empty() {
        assertTrue(session.getMessageHandlers().isEmpty());
    }

    @Test
    void removeMessageHandler_noop() {
        session.removeMessageHandler(mock(MessageHandler.class));
    }

    @Test
    void getProtocolVersion_13() {
        assertEquals("13", session.getProtocolVersion());
    }

    @Test
    void getNegotiatedSubprotocol_delegates() {
        when(springSession.getAcceptedProtocol()).thenReturn("chat");
        assertEquals("chat", session.getNegotiatedSubprotocol());
    }

    @Test
    void getNegotiatedExtensions_empty() {
        assertTrue(session.getNegotiatedExtensions().isEmpty());
    }

    @Test
    void isSecure_checksScheme() {
        when(springSession.getUri()).thenReturn(URI.create("wss://host/ws"));
        assertTrue(session.isSecure());
        when(springSession.getUri()).thenReturn(URI.create("ws://host/ws"));
        assertFalse(session.isSecure());
    }

    @Test
    void isOpen_delegates() {
        when(springSession.isOpen()).thenReturn(true);
        assertTrue(session.isOpen());
    }

    @Test
    void maxIdleTimeout_getSet() {
        assertEquals(-1L, session.getMaxIdleTimeout());
        session.setMaxIdleTimeout(5000);
        assertEquals(5000L, session.getMaxIdleTimeout());
    }

    @Test
    void bufferSizes_delegate() {
        session.setMaxBinaryMessageBufferSize(100);
        verify(springSession).setBinaryMessageSizeLimit(100);
        session.setMaxTextMessageBufferSize(200);
        verify(springSession).setTextMessageSizeLimit(200);
        when(springSession.getBinaryMessageSizeLimit()).thenReturn(100);
        when(springSession.getTextMessageSizeLimit()).thenReturn(200);
        assertEquals(100, session.getMaxBinaryMessageBufferSize());
        assertEquals(200, session.getMaxTextMessageBufferSize());
    }

    @Test
    void remoteEndpoints_available() {
        assertNotNull(session.getAsyncRemote());
        assertNotNull(session.getBasicRemote());
        assertTrue(session.getAsyncRemote() instanceof RemoteEndpoint.Async);
        assertTrue(session.getBasicRemote() instanceof RemoteEndpoint.Basic);
    }

    @Test
    void getId_delegates() {
        when(springSession.getId()).thenReturn("s1");
        assertEquals("s1", session.getId());
    }

    @Test
    void close_noArg_delegates() throws IOException {
        session.close();
        verify(springSession).close();
    }

    @Test
    void close_withReason_delegatesWithStatus() throws IOException {
        session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "bye"));
        verify(springSession).close(new CloseStatus(1000, "bye"));
    }

    @Test
    void getRequestURI_delegates() {
        URI uri = URI.create("ws://h/ws");
        when(springSession.getUri()).thenReturn(uri);
        assertSame(uri, session.getRequestURI());
    }

    @Test
    void getQueryString_fromUri() {
        when(springSession.getUri()).thenReturn(URI.create("ws://h/ws?token=abc&x=1"));
        assertEquals("token=abc&x=1", session.getQueryString());
    }

    @Test
    void getRequestParameterMap_parsesQuery() {
        when(springSession.getUri()).thenReturn(URI.create("ws://h/ws?token=abc&x=1&x=2"));
        Map<String, java.util.List<String>> params = session.getRequestParameterMap();
        assertEquals(2, params.size());
        assertEquals(Collections.singletonList("abc"), params.get("token"));
        assertEquals(java.util.List.of("1", "2"), params.get("x"));
    }

    @Test
    void getRequestParameterMap_noQuery_empty() {
        when(springSession.getUri()).thenReturn(URI.create("ws://h/ws"));
        assertTrue(session.getRequestParameterMap().isEmpty());
    }

    @Test
    void getPathParameters_returnsInjected() {
        assertEquals("42", session.getPathParameters().get("roomId"));
    }

    @Test
    void getUserProperties_mutable() {
        session.getUserProperties().put("k", "v");
        assertEquals("v", session.getUserProperties().get("k"));
    }

    @Test
    void getUserPrincipal_delegates() {
        Principal principal = mock(Principal.class);
        when(springSession.getPrincipal()).thenReturn(principal);
        assertSame(principal, session.getUserPrincipal());
    }

    @Test
    void getOpenSessions_empty() {
        assertTrue(session.getOpenSessions().isEmpty());
    }
}
