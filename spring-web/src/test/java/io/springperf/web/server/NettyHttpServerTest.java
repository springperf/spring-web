package io.springperf.web.server;

import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NettyHttpServerTest {

    @Test
    void constructor_storesWebContext() {
        WebContext webContext = mock(WebContext.class);
        NettyHttpServer server = new NettyHttpServer(webContext);
        assertNotNull(server);
    }

    @Test
    void getPhase_returnsMaxValue() {
        WebContext webContext = mock(WebContext.class);
        NettyHttpServer server = new NettyHttpServer(webContext);
        assertEquals(Integer.MAX_VALUE, server.getPhase());
    }

    @Test
    void isRunning_beforeStart_returnsFalse() {
        WebContext webContext = mock(WebContext.class);
        NettyHttpServer server = new NettyHttpServer(webContext);
        assertFalse(server.isRunning());
    }

    @Test
    void stop_beforeStart_doesNotThrow() {
        WebContext webContext = mock(WebContext.class);
        NettyHttpServer server = new NettyHttpServer(webContext);
        // stop(Runnable) on unstarted server catches NPE from null channel/group,
        // logs it, then runs callback. Should not propagate exception.
        assertDoesNotThrow(() -> server.stop(() -> {}));
    }

    @Test
    void stop_beforeStart_setsRunningToFalse() {
        WebContext webContext = mock(WebContext.class);
        NettyHttpServer server = new NettyHttpServer(webContext);
        server.stop(() -> {});
        assertFalse(server.isRunning());
    }

    @Test
    void stop_beforeStart_callsCallback() {
        WebContext webContext = mock(WebContext.class);
        NettyHttpServer server = new NettyHttpServer(webContext);
        final boolean[] called = {false};
        server.stop(() -> called[0] = true);
        assertTrue(called[0]);
    }

    @Test
    void stop_noArg_delegatesToStopWithCallback() {
        WebContext webContext = mock(WebContext.class);
        NettyHttpServer server = spy(new NettyHttpServer(webContext));
        // stop() calls stop(Runnable) internally; verify delegation
        server.stop();
        verify(server, atLeastOnce()).stop(any(Runnable.class));
    }
}