package io.springperf.web.server;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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

    /**
     * 验证 {@code destroyComponent()} 真正消费 {@code server.shutdown.grace-period}：
     * 以 {@code shutdownGracefully(0, graceMillis, MILLISECONDS)} 关闭两个 EventLoopGroup
     * （quietPeriod=0，最多等待 grace 让在途请求排空）。
     */
    @Test
    void destroyComponent_passesGracePeriodToShutdownGracefully() throws Exception {
        WebContext webContext = mock(WebContext.class);
        NettyHttpServer server = new NettyHttpServer(webContext);

        EventLoopGroup boss = mock(EventLoopGroup.class);
        EventLoopGroup worker = mock(EventLoopGroup.class);
        Future<?> future = mock(Future.class);
        doReturn(future).when(boss).shutdownGracefully(anyLong(), anyLong(), any(TimeUnit.class));
        doReturn(future).when(worker).shutdownGracefully(anyLong(), anyLong(), any(TimeUnit.class));
        doReturn(future).when(future).sync();

        setField(server, "bossGroup", boss);
        setField(server, "workerGroup", worker);
        // 自定义 grace（绕过 start()，直接验证 destroyComponent 的消费逻辑）
        setField(server, "shutdownGraceMillis", 12345L);

        server.destroyComponent();

        verify(boss).shutdownGracefully(eq(0L), eq(12345L), eq(TimeUnit.MILLISECONDS));
        verify(worker).shutdownGracefully(eq(0L), eq(12345L), eq(TimeUnit.MILLISECONDS));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}