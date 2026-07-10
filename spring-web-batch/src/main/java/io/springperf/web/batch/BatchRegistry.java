package io.springperf.web.batch;

import io.springperf.web.batch.common.BatchHandlerRegistration;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class BatchRegistry extends BaseWebComponent {

    private static final MappingCacheKey<BatchRequestMetaData> BATCH_META_CACHE_KEY =
            MappingCacheKey.createMethodCacheKey(BatchRequestMetaData.class);

    private final Map<String, DisruptorQueue> queues = new ConcurrentHashMap<>();
    private final List<BatchHandlerRegistration> registrations = new ArrayList<>();
    private BatchMetrics metrics = NoOpBatchMetrics.INSTANCE;

    /**
     * Set a custom metrics collector for all batch queues.
     * Must be called before {@link #initComponentPhase2()} takes effect.
     *
     * @param metrics metrics collector, or {@code null} to disable
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

        List<BatchHandlerRegistration> registrations = new BatchScanner()
                .scan(ctx, mappingRegistry.getMappingContextList());

        for (BatchHandlerRegistration reg : registrations) {
            install(reg);
        }
        this.registrations.addAll(registrations);
    }

    @Override
    public void destroyComponent() throws Exception {
        for (DisruptorQueue queue : queues.values()) {
            queue.shutdown();
        }
        queues.clear();
        registrations.clear();
        log.info("BatchRegistry destroyed");
    }

    // ------------------------------------------------------------------
    // Installation
    // ------------------------------------------------------------------

    private void install(BatchHandlerRegistration reg) {
        BatchRequestMetaData meta = reg.meta();

        // cache meta on the single method for BatchInvoker
        reg.singleCtx().set(BATCH_META_CACHE_KEY, meta);

        // create a dedicated Disruptor queue per @BatchMapping method
        String queueName = meta.queueName();
        DisruptorQueue queue = new DisruptorQueue(queueName, meta, reg.bean(), metrics);
        DisruptorQueue existing = queues.putIfAbsent(queueName, queue);
        if (existing != null) {
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

        // 默认在 EventLoop 完成入队列前处理；用户指定 @RunInPool 时尊重其选择
        BizPoolRegistry poolRegistry = webContext.getWebComponent(BizPoolRegistry.class);
        if (poolRegistry != null) {
            poolRegistry.setDefaultPool(reg.singleCtx(), null);
        }
    }
}