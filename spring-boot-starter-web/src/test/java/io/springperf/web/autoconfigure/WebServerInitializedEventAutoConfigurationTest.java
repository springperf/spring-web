package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class WebServerInitializedEventAutoConfigurationTest {

    @Test
    void publisher_whenRunning_publishesEvent() {
        WebServerInitializedEventAutoConfiguration config = new WebServerInitializedEventAutoConfiguration();
        NettyHttpServer server = mock(NettyHttpServer.class);
        when(server.isRunning()).thenReturn(true);
        when(server.getActualPort()).thenReturn(8080);
        ApplicationContext ctx = mock(ApplicationContext.class);

        ApplicationListener<ApplicationReadyEvent> listener =
                config.webServerInitializedEventPublisher(server, ctx);
        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(server).isRunning();
        org.mockito.ArgumentCaptor<ApplicationEvent> captor =
                org.mockito.ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(ctx).publishEvent(captor.capture());
        assertTrue(captor.getValue() instanceof PerfWebServerInitializedEvent,
                "应发布 PerfWebServerInitializedEvent");
        PerfWebServerInitializedEvent event = (PerfWebServerInitializedEvent) captor.getValue();
        WebServer webServer = event.getWebServer();
        assertEquals(8080, webServer.getPort(), "事件 WebServer 应携带真实端口");
    }

    @Test
    void publisher_whenNotRunning_skipsPublish() {
        WebServerInitializedEventAutoConfiguration config = new WebServerInitializedEventAutoConfiguration();
        NettyHttpServer server = mock(NettyHttpServer.class);
        when(server.isRunning()).thenReturn(false);
        ApplicationContext ctx = mock(ApplicationContext.class);

        ApplicationListener<ApplicationReadyEvent> listener =
                config.webServerInitializedEventPublisher(server, ctx);
        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(ctx, never()).publishEvent(any(ApplicationEvent.class));
        verify(ctx, never()).publishEvent(any(Object.class));
    }
}
