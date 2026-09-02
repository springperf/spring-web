package io.springperf.web.autoconfigure.support;

import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PerfWebServerTest {

    @Test
    void getPort_returnsConfiguredPort() {
        NettyHttpServer server = mock(NettyHttpServer.class);
        PerfWebServer webServer = new PerfWebServer(8080, server);
        assertEquals(8080, webServer.getPort());
        assertSame(server, webServer.getNettyHttpServer());
    }

    @Test
    void start_isNoop() {
        NettyHttpServer server = mock(NettyHttpServer.class);
        PerfWebServer webServer = new PerfWebServer(8080, server);
        assertDoesNotThrow(webServer::start);
        verify(server, never()).start();
    }

    @Test
    void stop_delegatesToNettyHttpServer() {
        NettyHttpServer server = mock(NettyHttpServer.class);
        PerfWebServer webServer = new PerfWebServer(8080, server);
        webServer.stop();
        verify(server).stop(any(Runnable.class));
    }
}
