package io.springperf.web.server;

import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 核心模块服务器启动事件：发布时机、空上下文兜底、与 Boot lifecycle 的 phase 对齐。 */
class NettyServerStartedEventTest {

    @Test
    void publishServerStartedEvent_publishesFrameworkEvent() throws Exception {
        WebContext webContext = mock(WebContext.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(webContext.getCtx()).thenReturn(ctx);
        NettyHttpServer server = new NettyHttpServer(webContext);

        invokePublish(server);

        org.mockito.ArgumentCaptor<ApplicationEvent> captor = org.mockito.ArgumentCaptor
                .forClass(ApplicationEvent.class);
        verify(ctx).publishEvent(captor.capture());
        NettyServerStartedEvent event = assertInstanceOf(NettyServerStartedEvent.class, captor.getValue());
        assertSame(server, event.getNettyHttpServer());
        assertSame(server, event.getSource());
    }

    @Test
    void publishServerStartedEvent_withoutContext_isNoOp() {
        WebContext webContext = mock(WebContext.class);
        when(webContext.getCtx()).thenReturn(null);

        assertDoesNotThrow(() -> invokePublish(new NettyHttpServer(webContext)),
                "单测直接构造服务器、没有真实上下文时不得抛异常");
    }

    @Test
    void getPhase_matchesBootWebServerLifecycle() {
        assertEquals(Integer.MAX_VALUE - 2048, new NettyHttpServer(mock(WebContext.class)).getPhase(),
                "主服务器必须早于依赖 WebServerInitializedEvent 的默认 phase 组件启动");
    }

    private static void invokePublish(NettyHttpServer server) throws Exception {
        Method method = NettyHttpServer.class.getDeclaredMethod("publishServerStartedEvent");
        method.setAccessible(true);
        method.invoke(server);
    }
}
