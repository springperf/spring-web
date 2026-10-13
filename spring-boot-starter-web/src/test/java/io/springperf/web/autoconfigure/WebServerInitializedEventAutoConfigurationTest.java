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

/**
 * {@code PerfWebServerInitializedEvent} 的发布时机 —— Spring Cloud 服务注册（Nacos/Eureka/Consul）依赖该事件。
 *
 * <p><b>与 master 的差异（适配，非漏改）</b>：master 把发布器放在独立的
 * {@code WebServerInitializedEventAutoConfiguration}；2.7.x 的发布器 Bean 位于
 * {@link SpringWebAutoConfiguration#webServerInitializedEventPublisher(NettyHttpServer, ApplicationContext)}，
 * 断言的契约完全相同。</p>
 */
class WebServerInitializedEventAutoConfigurationTest {

    @Test
    void publisher_whenRunning_publishesEvent() {
        SpringWebAutoConfiguration config = new SpringWebAutoConfiguration();
        NettyHttpServer server = mock(NettyHttpServer.class);
        when(server.isRunning()).thenReturn(true);
        when(server.getActualPort()).thenReturn(8080);
        ApplicationContext ctx = mock(ApplicationContext.class);

        ApplicationListener<ApplicationReadyEvent> listener = config.webServerInitializedEventPublisher(server, ctx);
        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(server).isRunning();
        org.mockito.ArgumentCaptor<ApplicationEvent> captor = org.mockito.ArgumentCaptor
                .forClass(ApplicationEvent.class);
        verify(ctx).publishEvent(captor.capture());
        assertTrue(captor.getValue() instanceof PerfWebServerInitializedEvent, "应发布 PerfWebServerInitializedEvent");
        PerfWebServerInitializedEvent event = (PerfWebServerInitializedEvent) captor.getValue();
        WebServer webServer = event.getWebServer();
        assertEquals(8080, webServer.getPort(), "事件 WebServer 应携带真实端口");
    }

    @Test
    void publisher_whenNotRunning_skipsPublish() {
        SpringWebAutoConfiguration config = new SpringWebAutoConfiguration();
        NettyHttpServer server = mock(NettyHttpServer.class);
        when(server.isRunning()).thenReturn(false);
        ApplicationContext ctx = mock(ApplicationContext.class);

        ApplicationListener<ApplicationReadyEvent> listener = config.webServerInitializedEventPublisher(server, ctx);
        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(ctx, never()).publishEvent(any(ApplicationEvent.class));
        verify(ctx, never()).publishEvent(any(Object.class));
    }
}
