package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import io.springperf.web.server.NettyHttpServer;
import io.springperf.web.server.NettyServerStartedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebServerInitializedEventAutoConfigurationTest {

    @Test
    void publisher_onServerStartedEvent_publishesBootEvent() {
        WebServerInitializedEventAutoConfiguration config = new WebServerInitializedEventAutoConfiguration();
        NettyHttpServer server = mock(NettyHttpServer.class);
        when(server.isRunning()).thenReturn(true);
        when(server.getActualPort()).thenReturn(8080);
        ApplicationContext ctx = mock(ApplicationContext.class);

        ApplicationListener<NettyServerStartedEvent> listener = config.webServerInitializedEventPublisher(ctx);
        listener.onApplicationEvent(new NettyServerStartedEvent(server));

        org.mockito.ArgumentCaptor<ApplicationEvent> captor = org.mockito.ArgumentCaptor
                .forClass(ApplicationEvent.class);
        verify(ctx).publishEvent(captor.capture());
        assertTrue(captor.getValue() instanceof PerfWebServerInitializedEvent, "应发布 PerfWebServerInitializedEvent");
        PerfWebServerInitializedEvent event = (PerfWebServerInitializedEvent) captor.getValue();
        WebServer webServer = event.getWebServer();
        assertEquals(8080, webServer.getPort(), "事件 WebServer 应携带真实端口");
        assertSame(server, ((io.springperf.web.autoconfigure.support.PerfWebServer) webServer).getNettyHttpServer());
    }

    @Test
    void publisher_whenNotRunning_skipsPublish() {
        WebServerInitializedEventAutoConfiguration config = new WebServerInitializedEventAutoConfiguration();
        NettyHttpServer server = mock(NettyHttpServer.class);
        when(server.isRunning()).thenReturn(false);
        ApplicationContext ctx = mock(ApplicationContext.class);

        ApplicationListener<NettyServerStartedEvent> listener = config.webServerInitializedEventPublisher(ctx);
        listener.onApplicationEvent(new NettyServerStartedEvent(server));

        verify(ctx, never()).publishEvent(any(ApplicationEvent.class));
        verify(ctx, never()).publishEvent(any(Object.class));
    }

    @Test
    void publisher_doesNotDependOnApplicationReadyEvent() throws Exception {
        // 回归 #11：监听器必须订阅服务器启动事件，而不是 ApplicationReadyEvent
        java.lang.reflect.Method method = WebServerInitializedEventAutoConfiguration.class.getMethod(
                "webServerInitializedEventPublisher", ApplicationContext.class);
        String generic = method.getGenericReturnType().getTypeName();
        assertTrue(generic.contains(NettyServerStartedEvent.class.getName()),
                "监听器泛型应为 NettyServerStartedEvent，事件不得延迟到 ApplicationReadyEvent");
    }
}
