package io.springperf.web.autoconfigure;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpringWebAutoConfigurationTest {

    private final SpringWebAutoConfiguration config = new SpringWebAutoConfiguration();

    @Test
    void dispatcherHandler_createsNewDispatcherHandler() {
        DispatcherHandler handler = config.dispatcherHandler();
        assertNotNull(handler);
        assertInstanceOf(DispatcherHandler.class, handler);
    }

    @Test
    void dispatcherHandler_returnsNewInstanceEachCall() {
        assertNotSame(config.dispatcherHandler(), config.dispatcherHandler());
    }

    @Test
    void applicationProperties_createsNewApplicationProperties() {
        ApplicationProperties props = config.applicationProperties();
        assertNotNull(props);
        assertInstanceOf(ApplicationProperties.class, props);
    }

    @Test
    void applicationProperties_returnsNewInstanceEachCall() {
        assertNotSame(config.applicationProperties(), config.applicationProperties());
    }

    @Test
    void webContext_createsBeanWhenDispatcherHandlerAvailable() {
        DispatcherHandler handler = mock(DispatcherHandler.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(anyString(), anyString())).thenReturn("/");

        assertNotNull(config.webContext(java.util.Collections.singletonList(handler), props));
    }

    @Test
    void nettyHttpServer_createsNettyHttpServer() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        Environment environment = mock(Environment.class);
        when(environment.getProperty("server.http2.enabled", boolean.class, false)).thenReturn(false);

        NettyHttpServer server = config.nettyHttpServer(webContext, environment);
        assertNotNull(server);
        assertInstanceOf(NettyHttpServer.class, server);
    }
}