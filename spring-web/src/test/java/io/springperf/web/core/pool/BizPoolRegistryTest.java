package io.springperf.web.core.pool;

import io.springperf.web.annotation.RunInPool;
import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.metrics.WebMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 注意：各测试用例使用独立的 controller method name，
 * 避免 {@link MappingHandlerMethod} 静态缓存跨测试污染。
 */
@ExtendWith(MockitoExtension.class)
class BizPoolRegistryTest {

    private final BizPoolRegistry registry = new BizPoolRegistry();

    // ---------------------------------------------------------------
    // @RunInPool 无注解，未初始化 WebContext：返回 null（降级 EventLoop）
    // ---------------------------------------------------------------

    @Test
    void determinePool_noAnnotation_returnsNull() {
        MappingHandlerMethod mhm = mappingHandler(NoAnnotationCtrl.class, "noAnnotation");
        assertNull(registry.determinePool(mhm));
    }

    @Test
    void determinePool_noAnnotation_nullIsCached() {
        MappingHandlerMethod mhm = mappingHandler(NoAnnotationCtrl.class, "noAnnotation");
        assertNull(registry.determinePool(mhm));
        assertNull(registry.determinePool(mhm));
    }

    static class NoAnnotationCtrl {
        public void noAnnotation() {
        }
    }

    // ---------------------------------------------------------------
    // @RunInPool("default")：返回 default 线程池
    // ---------------------------------------------------------------

    @Test
    void determinePool_withDefaultAnnotation_returnsPool() {
        registry.register("default", new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
        MappingHandlerMethod mhm = mappingHandler(DefaultPoolCtrl.class, "withDefaultPool");
        assertNotNull(registry.determinePool(mhm));
    }

    @Test
    void determinePool_withDefaultAnnotation_resultIsCached() {
        registry.register("default", new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
        MappingHandlerMethod mhm = mappingHandler(DefaultPoolCtrl.class, "withDefaultPool");
        ExecutorService pool1 = registry.determinePool(mhm);
        ExecutorService pool2 = registry.determinePool(mhm);
        assertSame(pool1, pool2, "二次调用应返回同一实例");
    }

    static class DefaultPoolCtrl {
        @RunInPool("default")
        public void withDefaultPool() {
        }
    }

    // ---------------------------------------------------------------
    // @RunInPool("nonexistent")：池不存在，抛 IllegalStateException
    // ---------------------------------------------------------------

    @Test
    void determinePool_withInvalidPoolName_throwsIllegalState() {
        MappingHandlerMethod mhm = mappingHandler(InvalidPoolCtrl.class, "withInvalidPool");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.determinePool(mhm));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    static class InvalidPoolCtrl {
        @RunInPool("nonexistent")
        public void withInvalidPool() {
        }
    }

    // ---------------------------------------------------------------
    // @RunInPool(EVENTLOOP)：显式声明在 EventLoop 执行
    // ---------------------------------------------------------------

    @Test
    void determinePool_withEventLoopAnnotation_returnsNull() {
        registry.register("default", new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
        MappingHandlerMethod mhm = mappingHandler(EventLoopCtrl.class, "withEventLoopPool");
        assertNull(registry.determinePool(mhm));
    }

    @Test
    void determinePool_withEventLoopAnnotation_resultIsCached() {
        registry.register("default", new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
        MappingHandlerMethod mhm = mappingHandler(EventLoopCtrl.class, "withEventLoopPool");
        assertNull(registry.determinePool(mhm));
        assertNull(registry.determinePool(mhm));
    }

    static class EventLoopCtrl {
        @RunInPool(RunInPool.EVENTLOOP)
        public void withEventLoopPool() {
        }
    }

    // ---------------------------------------------------------------
    // register() 校验：禁止注册名为 "eventloop" 的池
    // ---------------------------------------------------------------

    @Test
    void register_withEventLoopName_throwsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.register("eventloop",
                        new ThreadPoolExecutor(1, 1, 0,
                                TimeUnit.SECONDS, new LinkedBlockingQueue<>())));
        assertTrue(ex.getMessage().contains("reserved keyword"));
    }

    @Test
    void register_withEventLoopNameCaseInsensitive_throwsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.register("EventLoop",
                        new ThreadPoolExecutor(1, 1, 0,
                                TimeUnit.SECONDS, new LinkedBlockingQueue<>())));
        assertTrue(ex.getMessage().contains("reserved keyword"));
    }

    // ---------------------------------------------------------------
    // 无 @RunInPool + pool.default-execute-mode=池名：走默认池
    // 使用独立 ctrl 避免静态缓存污染
    // ---------------------------------------------------------------

    @Test
    void determinePool_noAnnotation_withDefaultPoolConfig_returnsPool() {
        setWebContext(registry, "myPool");
        registry.register("myPool", new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
        MappingHandlerMethod mhm = mappingHandler(DefaultConfigCtrl.class, "noAnnotationWithDefaultConfig");
        assertNotNull(registry.determinePool(mhm));
    }

    @Test
    void determinePool_noAnnotation_withDefaultPoolConfig_resultIsCached() {
        setWebContext(registry, "myPool");
        registry.register("myPool", new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
        MappingHandlerMethod mhm = mappingHandler(DefaultConfigCtrl.class, "noAnnotationWithDefaultConfig");
        ExecutorService p1 = registry.determinePool(mhm);
        ExecutorService p2 = registry.determinePool(mhm);
        assertSame(p1, p2);
    }

    static class DefaultConfigCtrl {
        public void noAnnotationWithDefaultConfig() {
        }
    }

    // ---------------------------------------------------------------
    // 无 @RunInPool + pool.default-execute-mode=不存在的池：抛异常
    // ---------------------------------------------------------------

    @Test
    void determinePool_noAnnotation_withInvalidDefaultPool_throwsIllegalState() {
        setWebContext(registry, "nonexistent");
        MappingHandlerMethod mhm = mappingHandler(InvalidConfigCtrl.class, "noAnnotationWithInvalidConfig");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.determinePool(mhm));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    static class InvalidConfigCtrl {
        public void noAnnotationWithInvalidConfig() {
        }
    }

    // ---------------------------------------------------------------
    // 无 @RunInPool + pool.default-execute-mode=eventloop（显式 EventLoop）
    // ---------------------------------------------------------------

    @Test
    void determinePool_noAnnotation_withEventLoopConfig_returnsNull() {
        setWebContext(registry, "eventloop");
        MappingHandlerMethod mhm = mappingHandler(EventLoopConfigCtrl.class, "noAnnotationWithEventLoopConfig");
        assertNull(registry.determinePool(mhm));
    }

    static class EventLoopConfigCtrl {
        public void noAnnotationWithEventLoopConfig() {
        }
    }

    // ---------------------------------------------------------------
    // register() — metrics gauge registration
    // ---------------------------------------------------------------

    @Test
    void register_withThreadPoolExecutor_registersPoolGauges() {
        WebMetrics metrics = mock(WebMetrics.class);
        setMetrics(registry, metrics);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>());

        registry.register("myPool", executor);

        verify(metrics).registerPoolGauges("myPool", executor);
    }

    @Test
    void register_withNonThreadPoolExecutor_doesNotRegisterGauges() {
        WebMetrics metrics = mock(WebMetrics.class);
        setMetrics(registry, metrics);
        ExecutorService executor = mock(ExecutorService.class);

        registry.register("myPool", executor);

        verify(metrics, never()).registerPoolGauges(any(), any());
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    private static MappingHandlerMethod mappingHandler(Class<?> controllerClass, String methodName) {
        try {
            Object bean = controllerClass.getDeclaredConstructor().newInstance();
            return new MappingHandlerMethod(
                    new HandlerMethod(bean, controllerClass.getDeclaredMethod(methodName)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setWebContext(BizPoolRegistry registry, String defaultMode) {
        WebContext mockWebContext = mock(WebContext.class);
        try {
            Field field = BaseWebComponent.class.getDeclaredField("webContext");
            field.setAccessible(true);
            field.set(registry, mockWebContext);

            field = BizPoolRegistry.class.getDeclaredField("defaultExecuteMode");
            field.setAccessible(true);
            field.set(registry, defaultMode);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setMetrics(BizPoolRegistry registry, WebMetrics metrics) {
        try {
            Field field = BizPoolRegistry.class.getDeclaredField("metrics");
            field.setAccessible(true);
            field.set(registry, metrics);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}