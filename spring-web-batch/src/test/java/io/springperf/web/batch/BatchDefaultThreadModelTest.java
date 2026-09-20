package io.springperf.web.batch;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * batch 默认线程模型的**最终落点**验证（真实 {@link BizPoolRegistry} + 真实 {@link BatchRegistry}，
 * 不使用 mock 断言调用，而是看 {@code determinePool} 实际解析出哪个执行器）：
 *
 * <ul>
 *   <li>未开虚拟线程 → 遵循全局默认 {@code pool.default-execute-mode}（本例为 default 业务池）；</li>
 *   <li>开启虚拟线程（JDK 21+）→ EventLoop（{@code determinePool} 返回 null）。</li>
 * </ul>
 */
class BatchDefaultThreadModelTest {

    public static class EchoRequest extends BatchRequest<String> {
        public EchoRequest(String data) {
            this.setResult(data);
        }
    }

    /**
     * 未开虚拟线程场景。两个场景各用一个控制器类 + 各自的配置：{@code BatchScanner} 按 bean 扫描，
     * 且 {@link io.springperf.web.core.mapping.MappingHandlerMethod} 的方法级决策缓存是<b>按方法跨实例共享</b>的，
     * 同一 JVM 内必须让两个场景的方法互不相遇，否则后跑的用例会读到先跑者写入的决策。
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

    @Test
    void singlePath_usesDefaultBusinessPool_whenVirtualThreadsDisabled() throws Exception {
        ExecutorService resolved = resolveSingleDispatchExecutor(false);
        assertFalse(resolved == null, "未开虚拟线程时应落在 default 业务池，而不是 EventLoop");
    }

    @Test
    void singlePath_usesEventLoop_whenVirtualThreadsEnabled() throws Exception {
        assumeTrue(Runtime.version().feature() >= 21,
                "虚拟线程需要 JDK 21+，当前 JDK " + Runtime.version().feature());
        assertNull(resolveSingleDispatchExecutor(true), "开启虚拟线程时 single 路径应留在 EventLoop（零切换）");
    }

    /**
     * 装配真实的 BizPoolRegistry + BatchRegistry，返回 {@code @BatchMapping} single 处理器
     * 最终解析到的执行器（{@code null} = EventLoop），并校验它与虚拟线程开关的预期一致。
     */
    private ExecutorService resolveSingleDispatchExecutor(boolean virtualThreadsEnabled) throws Exception {
        Class<?> controllerType = virtualThreadsEnabled ? VtController.class : PlainController.class;
        Class<?> configType = virtualThreadsEnabled ? VtConfig.class : PlainConfig.class;
        String singleMethod = virtualThreadsEnabled ? "singleVt" : "single";
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(configType)) {
            Object bean = ctx.getBean(controllerType);
            HandlerMethod hm = new HandlerMethod(bean, controllerType.getMethod(singleMethod, String.class));
            PathMappingContext singleCtx = new PathMappingContext(hm, Collections.emptyList(), "/" + singleMethod);

            WebContext webContext = mock(WebContext.class);
            ApplicationProperties props = mock(ApplicationProperties.class);
            when(webContext.getCtx()).thenReturn(ctx);
            when(webContext.getProps()).thenReturn(props);
            when(webContext.getWebComponentWithDefault(eq(WebMetrics.class), any())).thenReturn(NoOpWebMetrics.INSTANCE);
            when(props.getBoolean(eq("spring.threads.virtual.enabled"), eq(false))).thenReturn(virtualThreadsEnabled);
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
                boolean virtual = poolRegistry.usesVirtualThreads();
                ExecutorService resolved = poolRegistry.determinePool(singleCtx);
                if (virtual) {
                    assertNull(resolved, "开启虚拟线程时应落在 EventLoop");
                } else {
                    assertSame(poolRegistry.getDefaultPool(), resolved, "未开虚拟线程时应落在 default 业务池");
                }
                return resolved;
            } finally {
                registry.destroyComponent();
                poolRegistry.destroyComponent();
            }
        }
    }
}
