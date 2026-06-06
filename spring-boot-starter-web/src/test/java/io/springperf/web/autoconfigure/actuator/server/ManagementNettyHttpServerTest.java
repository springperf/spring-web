package io.springperf.web.autoconfigure.actuator.server;

import io.springperf.web.context.WebContext;
import io.springperf.web.server.HttpHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ManagementNettyHttpServerTest {

    @Mock WebContext webContext;
    @Mock HttpHandler handler;

    @Test
    void constructor_with5Params_createsServer() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576);

        assertNotNull(server);
    }

    @Test
    void constructor_withSslContext_createsServer() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576, null);

        assertNotNull(server);
    }

    @Test
    void isRunning_initialState_returnsFalse() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576);

        assertFalse(server.isRunning());
    }

    @Test
    void getPhase_returnsMaxValue() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576);

        assertEquals(Integer.MAX_VALUE, server.getPhase());
    }

    @Test
    void stop_withoutStart_doesNotThrow() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576);

        assertDoesNotThrow(() -> server.stop());
    }

    @Test
    void stop_withoutStart_setsRunningToFalse() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576);

        server.stop();

        assertFalse(server.isRunning());
    }

    @Test
    void autoStartRequested_returnsTrue() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576);

        assertTrue(server.isAutoStartup());
    }
}