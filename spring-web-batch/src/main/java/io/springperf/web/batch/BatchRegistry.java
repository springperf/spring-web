package io.springperf.web.batch;

import io.springperf.web.batch.common.BatchDefaults;
import io.springperf.web.batch.common.BatchHandlerRegistration;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import io.springperf.web.batch.common.BatchScanner;
import io.springperf.web.batch.invoker.BatchInvoker;
import io.springperf.web.batch.metrics.BatchMetrics;
import io.springperf.web.batch.metrics.NoOpBatchMetrics;
import io.springperf.web.batch.queue.DisruptorQueue;
import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.pool.BizPoolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class BatchRegistry extends BaseWebComponent {

    private static final MappingCacheKey<BatchRequestMetaData> BATCH_META_CACHE_KEY = MappingCacheKey
            .createMethodCacheKey(BatchRequestMetaData.class);

    private final Map<String, DisruptorQueue> queues = new ConcurrentHashMap<>();
    private BatchMetrics metrics = NoOpBatchMetrics.INSTANCE;

    /**
     * Set a custom metrics collector for all batch queues. Must be called before {@link #initComponentPhase2()} takes
     * effect.
     *
     * @param metrics
     *            metrics collector, or {@code null} to disable
     */
    public void setMetrics(BatchMetrics metrics) {
        this.metrics = metrics != null ? metrics : NoOpBatchMetrics.INSTANCE;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void initComponentPhase2() throws Exception {
        ApplicationContext ctx = webContext.getCtx();
        MappingRegistry mappingRegistry = webContext.getWebComponent(MappingRegistry.class);

        // springperf.batch.*：全局默认值（启动期读一次，之后改键不重建队列）
        BatchDefaults defaults = BatchDefaults.fromProperties(webContext.getProps());
        log.info("Batch global defaults: {} (configured: {})", defaults.describe(),
                defaults.configuredKeys().isEmpty() ? "none, all built-in"
                        : String.join(", ", defaults.configuredKeys()));

        List<BatchHandlerRegistration> registrations = new BatchScanner().scan(ctx,
                mappingRegistry.getMappingContextList(), defaults);

        for (BatchHandlerRegistration reg : registrations) {
            install(reg);
        }
        warnOnSuboptimalConfig(registrations);
    }

    /**
     * 启动期对两类「配置看起来正常、实际拿不到收益或会超配」的组合显式告警：
     * <ul>
     * <li>窗口被关闭（{@code batchWindowMillis=0}，注解或 {@code springperf.batch.window-millis=0} 均可）：刷出时机回到
     * {@code endOfBatch}，批大小取决于 RingBuffer 瞬时积压，{@code maxBatchSize} 很难达到；</li>
     * <li>多个队列的 {@code consumerSize} 之和超过 CPU 核数：每个 {@code @BatchMapping} 都持有独立的 bizExecutor，
     * 默认值（核数）是按单队列设计的。</li>
     * </ul>
     */
    private void warnOnSuboptimalConfig(List<BatchHandlerRegistration> registrations) {
        List<String> noWindow = new ArrayList<>();
        long workerBudget = 0;
        for (BatchHandlerRegistration reg : registrations) {
            BatchRequestMetaData meta = reg.meta();
            if (meta.batchWindowMillis() <= 0) {
                noWindow.add(meta.queueName());
            }
            workerBudget += meta.consumerSize();
        }
        if (!noWindow.isEmpty()) {
            log.warn("Batch queues with batchWindowMillis resolved to <= 0 ({}): {} - flushing falls back to "
                    + "endOfBatch, so batch size depends on momentary RingBuffer backlog and maxBatchSize is rarely "
                    + "reached; keep the default 2ms window unless the extra latency is unacceptable", noWindow.size(),
                    String.join(", ", noWindow));
        }
        int cores = Runtime.getRuntime().availableProcessors();
        if (registrations.size() > 1 && workerBudget > cores) {
            log.warn("Batch config allows up to {} concurrent batch workers across {} queues (CPU cores={}) - "
                    + "each @BatchMapping owns a dedicated bizExecutor; set consumerSize explicitly so that the "
                    + "sum stays within the core count", workerBudget, registrations.size(), cores);
        }
    }

    @Override
    public void destroyComponent() throws Exception {
        for (DisruptorQueue queue : queues.values()) {
            try {
                queue.shutdown();
            } catch (Exception e) {
                // 单个队列停机失败不能阻断其余队列的清理：Disruptor 消费者线程与 bizExecutor
                // 线程都是非 daemon，漏关任何一个都会让 JVM 无法退出。
                log.error("Error shutting down batch queue [{}]", queue.queueName(), e);
            }
        }
        queues.clear();
        log.info("BatchRegistry destroyed");
    }

    // ------------------------------------------------------------------
    // Installation
    // ------------------------------------------------------------------

    private void install(BatchHandlerRegistration reg) {
        BatchRequestMetaData meta = reg.meta();

        // cache meta on the single method for BatchInvoker
        reg.singleCtx().set(BATCH_META_CACHE_KEY, meta);

        // 开启虚拟线程（JDK 21+）时，批量方法本身也改用虚拟线程执行（见 DisruptorQueue 构造说明）
        BizPoolRegistry poolRegistry = webContext.getWebComponent(BizPoolRegistry.class);
        boolean virtualThreads = poolRegistry != null && poolRegistry.usesVirtualThreads();

        // create a dedicated Disruptor queue per @BatchMapping method
        String queueName = meta.queueName();
        DisruptorQueue queue = new DisruptorQueue(queueName, meta, reg.bean(), metrics, virtualThreads);
        DisruptorQueue existing = queues.putIfAbsent(queueName, queue);
        if (existing != null) {
            // 同 queueName 重复安装：刚创建的 queue 其 Disruptor 线程与 bizExecutor 已启动，
            // 必须先 shutdown 释放资源再抛异常（修复前直接抛导致线程泄漏）。
            queue.shutdown();
            throw new IllegalStateException(
                    "Queue [" + queueName + "] already exists — each @BatchMapping must have a unique queue. "
                            + "Conflict detected on @BatchMapping method [" + meta.batchMethod().getName()
                            + "] in class [" + meta.beanType().getName() + "]");
        }

        // replace the invoker with a batch enqueue invoker
        // NOTE: the original @RequestMapping method body will NOT be executed
        log.warn("@BatchMapping replaces method [{}] body — the original code in @RequestMapping will NOT execute",
                reg.singleCtx().getMethod().toGenericString());
        reg.singleCtx().setInvoker(new BatchInvoker(meta, queue));

        // 矫正返回值类型为 BatchRequest 子类，让 ReturnValueResolverRegistry 能正确解析泛型内联类型
        reg.singleCtx().setEffectiveReturnType(createEffectiveReturnType(meta));

        // single 路径的默认线程模型：
        // - 开启虚拟线程（JDK 21+）：default 池以虚拟线程执行任务，而 single 路径只做入队
        // （微秒级、不阻塞），走它反而多一次线程切换 → 保持 EventLoop 零切换；
        // - 未开启（含属性开启但 JDK < 21 回落的情形）：不设置，遵循全局默认线程模型
        // pool.default-execute-mode（默认 default 业务池）。
        // 两种情况下用户显式 @RunInPool 都优先（setDefaultPool 仅在无注解时生效）。
        if (virtualThreads) {
            poolRegistry.setDefaultPool(reg.singleCtx(), null);
        }
    }

    /**
     * 创建 BatchRequest 子类的合成返回类型，使 ReturnValueResolverRegistry 能正确解析泛型内联类型（如 EchoBatchRequest extends
     * BatchRequest<String> 中的 String）。 通过 ResolvableType 沿继承链向上查找，支持多层继承场景。
     */
    private static MethodParameter createEffectiveReturnType(BatchRequestMetaData meta) {
        Class<? extends BatchRequest<?>> requestType = meta.requestType();
        Type effectiveGenericType = resolveBatchRequestGenericType(requestType);
        return new MethodParameter(meta.batchMethod(), -1) {
            @Override
            public Class<?> getParameterType() {
                return requestType;
            }

            @Override
            public Type getGenericParameterType() {
                return effectiveGenericType != null ? effectiveGenericType : requestType.getGenericSuperclass();
            }
        };
    }

    private static Type resolveBatchRequestGenericType(Class<?> requestType) {
        ResolvableType rt = ResolvableType.forClass(requestType).as(BatchRequest.class);
        return rt.hasGenerics() ? rt.getType() : null;
    }
}
