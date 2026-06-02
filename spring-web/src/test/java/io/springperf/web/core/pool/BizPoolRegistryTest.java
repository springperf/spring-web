package io.springperf.web.core.pool;

import io.springperf.web.annotation.RunInPool;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.method.HandlerMethod;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class BizPoolRegistryTest {

    private final BizPoolRegistry registry = new BizPoolRegistry();

    // ---------------------------------------------------------------
    // @RunInPool 无注解：返回 null 并缓存
    // ---------------------------------------------------------------

    @Test
    void determinePool_noAnnotation_returnsNull() {
        MappingHandlerMethod mhm = mappingHandler("noAnnotation");

        assertNull(registry.determinePool(mhm));
    }

    @Test
    void determinePool_noAnnotation_nullIsCached() {
        MappingHandlerMethod mhm = mappingHandler("noAnnotation");

        // 首次调用 → null
        assertNull(registry.determinePool(mhm));
        // 再次调用 → 应走缓存直接返回 null，而不是重新反射
        assertNull(registry.determinePool(mhm));
    }

    // ---------------------------------------------------------------
    // @RunInPool("default")：返回 default 线程池
    // ---------------------------------------------------------------

    @Test
    void determinePool_withDefaultAnnotation_returnsPool() {
        registry.register("default", new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
        MappingHandlerMethod mhm = mappingHandler("withDefaultPool");

        assertNotNull(registry.determinePool(mhm));
    }

    @Test
    void determinePool_withDefaultAnnotation_resultIsCached() {
        registry.register("default", new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
        MappingHandlerMethod mhm = mappingHandler("withDefaultPool");

        ExecutorService pool1 = registry.determinePool(mhm);
        ExecutorService pool2 = registry.determinePool(mhm);

        assertSame(pool1, pool2, "二次调用应返回同一实例");
    }

    // ---------------------------------------------------------------
    // @RunInPool("nonexistent")：池不存在，抛 IllegalStateException
    // ---------------------------------------------------------------

    @Test
    void determinePool_withInvalidPoolName_throwsIllegalState() {
        MappingHandlerMethod mhm = mappingHandler("withInvalidPool");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.determinePool(mhm));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    static class TestController {
        public void noAnnotation() {
        }

        @RunInPool("default")
        public void withDefaultPool() {
        }

        @RunInPool("nonexistent")
        public void withInvalidPool() {
        }
    }

    private static MappingHandlerMethod mappingHandler(String methodName) {
        try {
            TestController bean = new TestController();
            return new MappingHandlerMethod(
                    new HandlerMethod(bean, TestController.class.getDeclaredMethod(methodName)));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
