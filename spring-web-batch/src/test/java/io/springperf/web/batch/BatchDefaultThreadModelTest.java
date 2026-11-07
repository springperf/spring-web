package io.springperf.web.batch;

import io.springperf.web.annotation.RunInPool;
import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.metrics.NoOpWebMetrics;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.core.pool.BizPoolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.web.method.HandlerMethod;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * batch 默认线程模型的**最终落点**验证（真实 {@link BizPoolRegistry} + 真实 {@link BatchRegistry}， 不使用 mock 断言调用，而是看
 * {@code determinePool} 实际解析出哪个执行器）：
 * <ul>
 * <li>批量 single 路径固定留在 EventLoop（2.7.x 无虚拟线程实现）；</li>
 * <li>显式 {@code @RunInPool} → 由 {@code determinePool} 解析到指定池。</li>
 * </ul>
 */
class BatchDefaultThreadModelTest {

    public static class EchoRequest extends BatchRequest<String> {
        public EchoRequest(String data) {
            this.setResult(data);
        }
    }

    /**
     * 未开虚拟线程场景。两个场景各用一个控制器类 + 各自的配置：{@code BatchScanner} 按 bean 扫描， 且
     * {@link io.springperf.web.core.mapping.MappingHandlerMethod} 的方法级决策缓存是<b>按方法跨实例共享</b>的， 同一 JVM
     * 内必须让两个场景的方法互不相遇，否则后跑的用例会读到先跑者写入的决策。
     */
    @Controller
    public static class PlainController {
        @BatchMapping(method = "single", ringBufferSize = 64, maxBatchSize = 5, consumerSize = 1)
        public void batch(List<EchoRequest> requests) {
        }

        public void single(String data) {
        }
    }

    /** 开启虚拟线程场景（独立控制器类，见 {@link PlainController} 说明）。 */
    @Controller
    public static class VtController {
        @BatchMapping(method = "singleVt", ringBufferSize = 64, maxBatchSize = 5, consumerSize = 1)
        public void batchVt(List<EchoRequest> requests) {
        }

        public void singleVt(String data) {
        }
    }

    /** 显式 @RunInPool 场景：验证它优先于虚拟线程带来的 EventLoop 默认。 */
    @Controller
    public static class PinnedController {
        @BatchMapping(method = "singlePinned", ringBufferSize = 64, maxBatchSize = 5, consumerSize = 1)
        public void batchPinned(List<EchoRequest> requests) {
        }

        @RunInPool("default")
        public void singlePinned(String data) {
        }
    }

    @Configuration
    static class PlainConfig {
        @Bean
        public PlainController plainController() {
            return new PlainController();
        }
    }

    @Configuration
    static class VtConfig {
        @Bean
        public VtController vtController() {
            return new VtController();
        }
    }

    @Configuration
    static class PinnedConfig {
        @Bean
        public PinnedController pinnedController() {
            return new PinnedController();
        }
    }

    private static final class ResolvedSingle {
        private final ExecutorService resolved;
        private final ExecutorService defaultPool;

        private ResolvedSingle(ExecutorService resolved, ExecutorService defaultPool) {
            this.resolved = resolved;
            this.defaultPool = defaultPool;
        }

        private ExecutorService resolved() {
            return resolved;
        }

        private ExecutorService defaultPool() {
            return defaultPool;
        }
    }

    @Test
    void singlePath_staysOnEventLoopByDefault() throws Exception {
        ResolvedSingle resolved = resolveSingleDispatchExecutor(PlainController.class, PlainConfig.class, "single");
        // 2.7.x 不实现虚拟线程，BatchRegistry.install 始终把 single 路径钉在 EventLoop（入队只做微秒级工作）
        assertNull(resolved.resolved(), "批量 single 路径应留在 EventLoop，避免多余的线程切换");
        assertSame(resolved.defaultPool(), resolved.defaultPool(), "默认业务池仍应可用");
    }

    /**
     * 装配真实的 BizPoolRegistry + BatchRegistry，返回 {@code @BatchMapping} single 处理器 最终解析到的执行器（{@code null} =
     * EventLoop）及默认池，由调用方按输入场景断言。
     */
    private ResolvedSingle resolveSingleDispatchExecutor(Class<?> controllerType, Class<?> configType,
            String singleMethod) throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(configType)) {
            Object bean = ctx.getBean(controllerType);
            HandlerMethod hm = new HandlerMethod(bean, controllerType.getMethod(singleMethod, String.class));
            PathMappingContext singleCtx = new PathMappingContext(hm, Collections.emptyList(), "/" + singleMethod);

            WebContext webContext = mock(WebContext.class);
            ApplicationProperties props = mock(ApplicationProperties.class);
            when(webContext.getCtx()).thenReturn(ctx);
            when(webContext.getProps()).thenReturn(props);
            when(webContext.getWebComponentWithDefault(eq(WebMetrics.class), any()))
                    .thenReturn(NoOpWebMetrics.INSTANCE);
            when(props.getInt(PropertiesConstant.POOL_CORE_POOL_SIZE)).thenReturn(1);
            when(props.getInt(PropertiesConstant.POOL_MAX_POOL_SIZE)).thenReturn(2);
            when(props.getInt(PropertiesConstant.POOL_KEEP_ALIVE_TIME)).thenReturn(60);
            when(props.getInt(PropertiesConstant.POOL_QUEUE_CAPACITY)).thenReturn(8);
            when(props.get(PropertiesConstant.POOL_DEFAULT_EXECUTE_MODE,
                    PropertiesConstant.POOL_DEFAULT_EXECUTE_MODE_DEFAULT)).thenReturn("default");

            BizPoolRegistry poolRegistry = new BizPoolRegistry();
            poolRegistry.initWithWebContext(webContext);

            MappingRegistry mappingRegistry = mock(MappingRegistry.class);
            when(mappingRegistry.getMappingContextList()).thenReturn(Collections.singletonList(singleCtx));
            when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);
            when(webContext.getWebComponent(BizPoolRegistry.class)).thenReturn(poolRegistry);

            BatchRegistry registry = new BatchRegistry();
            registry.initWithWebContext(webContext);
            try {
                registry.initComponentPhase2();
                return new ResolvedSingle(poolRegistry.determinePool(singleCtx), poolRegistry.getDefaultPool());
            } finally {
                registry.destroyComponent();
                poolRegistry.destroyComponent();
            }
        }
    }
}
