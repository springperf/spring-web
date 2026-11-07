package io.springperf.web.batch;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.invoker.BatchInvoker;
import io.springperf.web.batch.metrics.BatchMetrics;
import io.springperf.web.batch.metrics.NoOpBatchMetrics;
import io.springperf.web.batch.queue.DisruptorQueue;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.pool.BizPoolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 覆盖 {@link BatchRegistry#initComponentPhase2()} / {@link BatchRegistry#destroyComponent()}
 * / {@link BatchRegistry#setMetrics(BatchMetrics)}：真实 Spring 容器 + 真实 PathMappingContext，
 * 验证扫描 @BatchMapping → 安装队列 → 优雅关闭全流程。
 */
class BatchRegistryPhase2Test {

    public static class EchoRequest extends BatchRequest<String> {
        public EchoRequest(String data) {
            this.setResult(data);
        }
    }

    @Controller
    public static class BatchController {
        @BatchMapping(method = "single", ringBufferSize = 64, maxBatchSize = 5, consumerSize = 1)
        public void batch(List<EchoRequest> requests) {
        }

        public void single(String data) {
        }
    }

    @Configuration
    static class TestConfig {
        @Bean
        public BatchController batchController() {
            return new BatchController();
        }
    }

    private PathMappingContext mappingFor(Object bean, String methodName, Class<?>... paramTypes) throws Exception {
        HandlerMethod hm = new HandlerMethod(bean, bean.getClass().getMethod(methodName, paramTypes));
        return new PathMappingContext(hm, Collections.emptyList(), "/" + methodName);
    }

    @Test
    void initComponentPhase2_scansAndInstallsQueues_thenDestroys() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            BatchController bean = ctx.getBean(BatchController.class);
            PathMappingContext singleCtx = mappingFor(bean, "single", String.class);

            WebContext webContext = mock(WebContext.class);
            when(webContext.getCtx()).thenReturn(ctx);
            MappingRegistry mappingRegistry = mock(MappingRegistry.class);
            when(mappingRegistry.getMappingContextList()).thenReturn(Collections.singletonList(singleCtx));
            when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);
            when(webContext.getWebComponent(BizPoolRegistry.class)).thenReturn(null);

            BatchRegistry registry = new BatchRegistry();
            BatchMetrics metrics = mock(BatchMetrics.class);
            registry.setMetrics(metrics);
            registry.initWithWebContext(webContext);

            registry.initComponentPhase2();

            // 扫描 → 安装队列的完整结果必须可观测，否则整条链路改成空实现也测不出来
            Map<String, DisruptorQueue> queues = queuesOf(registry);
            assertEquals(1, queues.size(), "每个 @BatchMapping 应安装一个队列");
            DisruptorQueue queue = queues.get("batch:BatchController.batch");
            assertNotNull(queue, "队列 key 应为 batch:<bean>.<method>");
            assertEquals(64, queue.bufferSize(), "ringBufferSize 注解值应生效");
            assertTrue(singleCtx.getInvoker() instanceof BatchInvoker, "single 路径应被替换为 BatchInvoker");
            assertNotNull(singleCtx.getEffectiveReturnType(), "应设置泛型矫正后的返回值类型");
            assertSame(EchoRequest.class, singleCtx.getEffectiveReturnType().getParameterType());

            registry.destroyComponent();
            assertTrue(queues.isEmpty(), "destroy 必须清空队列注册表");
            // 幂等：二次 destroy 安全
            assertDoesNotThrow(() -> registry.destroyComponent());
        }
    }

    @Test
    void setMetrics_null_resetsToNoOp() throws Exception {
        BatchRegistry registry = new BatchRegistry();
        BatchMetrics custom = mock(BatchMetrics.class);

        registry.setMetrics(custom);
        assertSame(custom, metricsOf(registry), "非 null 指标应原样注入");

        registry.setMetrics(null);
        assertSame(NoOpBatchMetrics.INSTANCE, metricsOf(registry), "null 必须重置为 NoOp，而不是保留旧指标");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, DisruptorQueue> queuesOf(BatchRegistry registry) throws Exception {
        Field field = BatchRegistry.class.getDeclaredField("queues");
        field.setAccessible(true);
        return (Map<String, DisruptorQueue>) field.get(registry);
    }

    private static BatchMetrics metricsOf(BatchRegistry registry) throws Exception {
        Field field = BatchRegistry.class.getDeclaredField("metrics");
        field.setAccessible(true);
        return (BatchMetrics) field.get(registry);
    }
}
