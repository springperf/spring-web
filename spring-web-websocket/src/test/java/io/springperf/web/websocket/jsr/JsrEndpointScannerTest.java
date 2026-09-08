package io.springperf.web.websocket.jsr;

import javax.websocket.server.ServerEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JsrEndpointScannerTest {

    @ServerEndpoint("/ws/scanned")
    public static class ScannedEndpoint {}

    @Configuration
    static class TestConfig {
        @Bean
        public ScannedEndpoint scannedEndpoint() {
            return new ScannedEndpoint();
        }
    }

    @Test
    void scan_noBasePackages_returnsBeansOnly() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        JsrEndpointScanner scanner = new JsrEndpointScanner(ctx);
        List<Class<?>> classes = scanner.scan();
        assertFalse(classes.isEmpty(), "应至少扫描到 Spring Bean 注册的端点");
        assertTrue(classes.contains(ScannedEndpoint.class), "Bean 端点应被扫描到");
        ctx.close();
    }

    @Test
    void scan_nullContext_noThrow() {
        JsrEndpointScanner scanner = new JsrEndpointScanner(null);
        List<Class<?>> classes = scanner.scan();
        assertTrue(classes.isEmpty());
    }

    @Test
    void scan_withParentContext_skipsAutoPackages_noBeans() {
        ApplicationContext parent = mock(ApplicationContext.class);
        ApplicationContext child = mock(ApplicationContext.class);
        when(child.getParent()).thenReturn(parent);
        JsrEndpointScanner scanner = new JsrEndpointScanner(child);
        List<Class<?>> classes = scanner.scan();
        assertTrue(classes.isEmpty());
    }

    @Test
    void scan_beanNamesForAnnotationNull_returnsEmpty() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getParent()).thenReturn(null);
        // getBeanNamesForAnnotation 返回 null：扫描器应安全返回空，不抛 NPE
        when(ctx.getBeanNamesForAnnotation(ServerEndpoint.class)).thenReturn(null);
        JsrEndpointScanner scanner = new JsrEndpointScanner(ctx);
        assertTrue(scanner.scan().isEmpty());
    }

    @Test
    void scan_autoPackagesNotAvailable_returnsEmptyGracefully() {
        // 非 Spring Boot 主程序（无 AutoConfigurationPackages 注册）时不应抛异常
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getParent()).thenReturn(null);
        when(ctx.getBeanNamesForAnnotation(ServerEndpoint.class)).thenReturn(new String[0]);
        when(ctx.getAutowireCapableBeanFactory()).thenThrow(new IllegalStateException("no autowire bf"));
        JsrEndpointScanner scanner = new JsrEndpointScanner(ctx);
        assertTrue(scanner.scan().isEmpty());
    }

    @Test
    void scan_classpathDiscovery_findsAnnotatedEndpoint() {
        // 注册 AutoConfigurationPackages 基准包 → scanClasspath() 通过 classpath 扫描命中 @ServerEndpoint
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            org.springframework.boot.autoconfigure.AutoConfigurationPackages.register(
                    ctx.getDefaultListableBeanFactory(), "io.springperf.web.websocket.jsr");

            JsrEndpointScanner scanner = new JsrEndpointScanner(ctx);
            List<Class<?>> classes = scanner.scan();

            assertTrue(classes.contains(ScannedEndpoint.class),
                    "classpath 扫描应发现 @ServerEndpoint 类");
        }
    }

    @Test
    void scan_dedupsClasspathAndBeanResults() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            org.springframework.boot.autoconfigure.AutoConfigurationPackages.register(
                    ctx.getDefaultListableBeanFactory(), "io.springperf.web.websocket.jsr");
            ctx.register(TestConfig.class);
            ctx.refresh();

            JsrEndpointScanner scanner = new JsrEndpointScanner(ctx);
            List<Class<?>> classes = scanner.scan();

            assertEquals(1, classes.stream().filter(c -> c == ScannedEndpoint.class).count(),
                    "classpath 扫描与 Bean 扫描结果应去重");
        }
    }

    @Test
    void scan_beanWithoutType_skipped() {
        // 容器返回 type=null 的 bean 名应被安全跳过
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getParent()).thenReturn(null);
        when(ctx.getBeanNamesForAnnotation(ServerEndpoint.class)).thenReturn(new String[]{"ghost"});
        when(ctx.getType("ghost")).thenReturn(null);
        JsrEndpointScanner scanner = new JsrEndpointScanner(ctx);
        assertTrue(scanner.scan().isEmpty());
    }
}
