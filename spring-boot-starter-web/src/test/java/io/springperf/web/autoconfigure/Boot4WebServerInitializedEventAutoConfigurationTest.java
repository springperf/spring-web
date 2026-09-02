package io.springperf.web.autoconfigure;

import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class Boot4WebServerInitializedEventAutoConfigurationTest {

    @Test
    void publisher_whenRunning_publishesEvent() {
        Boot4WebServerInitializedEventAutoConfiguration config = new Boot4WebServerInitializedEventAutoConfiguration();
        NettyHttpServer server = mock(NettyHttpServer.class);
        when(server.isRunning()).thenReturn(true);
        when(server.getActualPort()).thenReturn(8080);
        ApplicationContext ctx = mock(ApplicationContext.class);

        ApplicationListener<ApplicationReadyEvent> listener =
                config.boot4WebServerInitializedEventPublisher(server, ctx);
        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        // 桥接以 Object 静态类型调用 publishEvent(Object) 重载
        verify(ctx, atLeastOnce()).publishEvent(any(Object.class));
    }

    @Test
    void publisher_whenNotRunning_skipsPublish() {
        Boot4WebServerInitializedEventAutoConfiguration config = new Boot4WebServerInitializedEventAutoConfiguration();
        NettyHttpServer server = mock(NettyHttpServer.class);
        when(server.isRunning()).thenReturn(false);
        ApplicationContext ctx = mock(ApplicationContext.class);

        ApplicationListener<ApplicationReadyEvent> listener =
                config.boot4WebServerInitializedEventPublisher(server, ctx);
        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(ctx, never()).publishEvent(any(ApplicationEvent.class));
        verify(ctx, never()).publishEvent(any(Object.class));
    }

    @Test
    void publisher_bridgeFailure_warnsAndDoesNotThrow() {
        Boot4WebServerInitializedEventAutoConfiguration config = new Boot4WebServerInitializedEventAutoConfiguration();
        NettyHttpServer server = mock(NettyHttpServer.class);
        when(server.isRunning()).thenReturn(true);
        ApplicationContext ctx = mock(ApplicationContext.class);
        doThrow(new RuntimeException("bridge fail")).when(ctx).publishEvent(any(Object.class));

        ApplicationListener<ApplicationReadyEvent> listener =
                config.boot4WebServerInitializedEventPublisher(server, ctx);
        assertDoesNotThrow(() -> listener.onApplicationEvent(mock(ApplicationReadyEvent.class)));
    }
}
