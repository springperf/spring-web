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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 覆盖 {@link BatchRegistry#initComponentPhase2()} / {@link BatchRegistry#destroyComponent()} /
 * {@link BatchRegistry#setMetrics(BatchMetrics)}：真实 Spring 容器 + 真实 PathMappingContext， 验证扫描 @BatchMapping → 安装队列 →
 * 优雅关闭全流程。
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

    /**
     * 开启虚拟线程（JDK 21+）时：single 路径 pin 到 EventLoop（其默认池是"每请求一个虚拟线程"的 不池化模型，而入队只需微秒级，走默认池反而多一次虚拟线程创建 + 切换）。
     */
    @Test
    void install_virtualThreadsEnabled_defaultsToEventLoop() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            BatchController bean = ctx.getBean(BatchController.class);
            PathMappingContext singleCtx = mappingFor(bean, "single", String.class);

            WebContext webContext = mock(WebContext.class);
            when(webContext.getCtx()).thenReturn(ctx);
            MappingRegistry mappingRegistry = mock(MappingRegistry.class);
            when(mappingRegistry.getMappingContextList()).thenReturn(Collections.singletonList(singleCtx));
            when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);
            BizPoolRegistry poolRegistry = mock(BizPoolRegistry.class);
            when(poolRegistry.usesVirtualThreads()).thenReturn(true);
            when(webContext.getWebComponent(BizPoolRegistry.class)).thenReturn(poolRegistry);

            BatchRegistry registry = new BatchRegistry();
            registry.initWithWebContext(webContext);
            try {
                registry.initComponentPhase2();
                verify(poolRegistry).setDefaultPool(singleCtx, null);
            } finally {
                registry.destroyComponent();
            }
        }
    }

    /**
     * 未开启虚拟线程（含属性开启但 JDK &lt; 21 回落）：不得覆盖全局默认线程模型， 让 single 路径遵循 {@code pool.default-execute-mode}（默认 default 业务池）。
     */
    @Test
    void install_virtualThreadsDisabled_doesNotOverrideGlobalDefault() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            BatchController bean = ctx.getBean(BatchController.class);
            PathMappingContext singleCtx = mappingFor(bean, "single", String.class);

            WebContext webContext = mock(WebContext.class);
            when(webContext.getCtx()).thenReturn(ctx);
            MappingRegistry mappingRegistry = mock(MappingRegistry.class);
            when(mappingRegistry.getMappingContextList()).thenReturn(Collections.singletonList(singleCtx));
            when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);
            BizPoolRegistry poolRegistry = mock(BizPoolRegistry.class);
            when(poolRegistry.usesVirtualThreads()).thenReturn(false);
            when(webContext.getWebComponent(BizPoolRegistry.class)).thenReturn(poolRegistry);

            BatchRegistry registry = new BatchRegistry();
            registry.initWithWebContext(webContext);
            try {
                registry.initComponentPhase2();
                assertEquals(1, queuesOf(registry).size(), "未开虚拟线程也应正常安装队列");
                verify(poolRegistry, never()).setDefaultPool(any(), any());
            } finally {
                registry.destroyComponent();
            }
        }
    }

    /**
     * 默认 {@code batchWindowMillis=0} 时批大小实测长期在 1~3，{@code maxBatchSize} 形同虚设。 启动期必须显式告警，不能静默退化成「没有批处理」。
     */
    @Test
    void initComponentPhase2_warnsWhenWindowDisabled() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(BatchRegistry.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
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
            registry.initWithWebContext(webContext);
            registry.initComponentPhase2();
            try {
                boolean warned = appender.list.stream()
                        .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                                && e.getFormattedMessage().contains("batchWindowMillis"));
                assertTrue(warned, "未设置 batchWindowMillis 的队列应在启动期告警，实际日志: " + appender.list);
            } finally {
                registry.destroyComponent();
            }
        } finally {
            logger.detachAppender(appender);
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
