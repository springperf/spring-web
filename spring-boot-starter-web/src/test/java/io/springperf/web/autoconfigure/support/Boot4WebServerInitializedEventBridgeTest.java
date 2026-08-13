package io.springperf.web.autoconfigure.support;

import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link Boot4WebServerInitializedEventBridge} 正向测试（SB4 桩环境）。
 *
 * <p>桥接设计为"编译期零引用 SB4 类"（SB3 下可加载、发布时门卫降级）。本测试在
 * test classpath 提供 SB4 包名桩（{@code org.springframework.boot.web.server.context.*}，
 * SB3 的 spring-boot jar 无该包），使 {@code Class.forName} 门卫通过，从而真实执行
 * ASM 生成子类 + {@code defineClass} + 反射实例化 + 发布事件的成功路径。</p>
 *
 * <p>桩仅在 test scope，不进入发布 jar，主代码编译期仍零引用 SB4 类；SB3 门卫路径
 * （真实 SB3 classpath 下 publish 抛 ClassNotFoundException）由真实运行时保证。</p>
 */
class Boot4WebServerInitializedEventBridgeTest {

    @Test
    void publish_generatesEvent_publishesPortAndContextProxy() throws Exception {
        NettyHttpServer server = mock(NettyHttpServer.class);
        when(server.getActualPort()).thenReturn(8080);

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getDisplayName()).thenReturn("test-ctx");

        Boot4WebServerInitializedEventBridge.publish(server, ctx);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).publishEvent(captor.capture());

        // ASM 生成的子类可强转为 SB4 桩事件类型
        WebServerInitializedEvent event = (WebServerInitializedEvent) captor.getValue();
        assertNotNull(event, "publish 应发布事件");
        // WebServer 包装：端口来自 NettyHttpServer.getActualPort()
        assertEquals(8080, event.getWebServer().getPort(), "事件 WebServer 应携带真实端口");

        WebServerApplicationContext appCtx = event.getApplicationContext();
        assertNotNull(appCtx, "事件应携带 SB4 WebServerApplicationContext 代理");
        assertEquals(8080, appCtx.getWebServer().getPort(), "上下文代理应暴露同一 WebServer");
        // 代理对 getServerNamespace 特判返回 null
        assertNull(appCtx.getServerNamespace(), "getServerNamespace 应为 null");
        // 代理对其它方法委托真实 ApplicationContext
        assertEquals("test-ctx", appCtx.getDisplayName(), "上下文代理应委托真实 ApplicationContext");
    }

    @Test
    void publish_concurrent_allSucceed_noLinkageError() throws Exception {
        NettyHttpServer server = mock(NettyHttpServer.class);
        when(server.getActualPort()).thenReturn(8080);
        ApplicationContext ctx = mock(ApplicationContext.class);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        Boot4WebServerInitializedEventBridge.publish(server, ctx);
                    } catch (Throwable t) {
                        // C6：并发 defineClass 竞态会抛 LinkageError；锁保护下不得失败
                        fail("SB4 桩环境下并发 publish 不应失败，实际: " + t);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }

        // 捕获全部发布事件；doAnswer 对 void 默认方法 publishEvent(ApplicationEvent)
        // 有 stubbing 匹配怪癖，故用与单测一致的 verify + ArgumentCaptor
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx, times(16)).publishEvent(captor.capture());
        assertEquals(16, captor.getAllValues().size(),
                "16 次并发 publish 应全部发布事件（C6 修复前部分失败/抛 LinkageError）");
        for (Object event : captor.getAllValues()) {
            assertInstanceOf(WebServerInitializedEvent.class, event, "每个事件都应是生成的 SB4 事件子类");
        }
    }
}
