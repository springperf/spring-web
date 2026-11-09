package io.springperf.web.autoconfigure.support;

import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerfWebServerInitializedEventTest {

    @Test
    void event_holdsWebServerAndProxiedContext() {
        NettyHttpServer server = mock(NettyHttpServer.class);
        when(server.getActualPort()).thenReturn(9090);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getDisplayName()).thenReturn("app-ctx");

        WebServer webServer = new PerfWebServer(9090, server);
        PerfWebServerInitializedEvent event = new PerfWebServerInitializedEvent(webServer, ctx);

        assertSame(webServer, event.getWebServer());
        WebServerApplicationContext appCtx = event.getApplicationContext();
        assertNotNull(appCtx);
        // getWebServer 特判返回给定 WebServer
        assertSame(webServer, appCtx.getWebServer());
        // getServerNamespace 特判返回 null
        assertNull(appCtx.getServerNamespace());
        // 其余方法委托真实 ApplicationContext
        assertEquals("app-ctx", appCtx.getDisplayName());
        // 事件 source 是 WebServer（父类构造 super(webServer)）
        assertSame(webServer, event.getSource());
        assertTrue(event instanceof WebServerInitializedEvent);
    }

    @Test
    void proxy_alsoImplementsConfigurableApplicationContext() {
        NettyHttpServer server = mock(NettyHttpServer.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getDisplayName()).thenReturn("delegate-name");

        PerfWebServerInitializedEvent event = new PerfWebServerInitializedEvent(new PerfWebServer(8080, server), ctx);
        WebServerApplicationContext appCtx = event.getApplicationContext();

        ConfigurableApplicationContext configurable = assertInstanceOf(ConfigurableApplicationContext.class, appCtx,
                "生态组件常把 event.getApplicationContext() 强转为 ConfigurableApplicationContext");
        assertEquals("delegate-name", configurable.getDisplayName(), "代理方法仍委托给真实上下文");
    }

    @Test
    void proxy_delegatesNonSpecialMethods() {
        NettyHttpServer server = mock(NettyHttpServer.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getDisplayName()).thenReturn("delegate-name");
        when(ctx.getId()).thenReturn("delegate-id");

        WebServer webServer = new PerfWebServer(8080, server);
        PerfWebServerInitializedEvent event = new PerfWebServerInitializedEvent(webServer, ctx);
        WebServerApplicationContext appCtx = event.getApplicationContext();

        assertEquals("delegate-name", appCtx.getDisplayName());
        assertEquals("delegate-id", appCtx.getId());
    }
}
