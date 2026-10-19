package io.springperf.web.batch.queue;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchOverflowException;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import io.springperf.web.batch.metrics.BatchMetrics;
import io.springperf.web.batch.metrics.NoOpBatchMetrics;
import io.springperf.web.core.pool.VirtualThreadSupport;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class DisruptorQueue {

    private final Disruptor<BatchEvent> disruptor;
    private final RingBuffer<BatchEvent> ringBuffer;
    private final ThreadPoolExecutor bizExecutor;
    private final BatchEventTranslator translator = new BatchEventTranslator();
    private final BatchMapping.Backpressure backpressure;
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private final String queueName;
    private final BufferingBatchHandler batchHandler;
    private final BatchMetrics metrics;

    public DisruptorQueue(String queueName, BatchRequestMetaData meta, Object bean) {
        this(queueName, meta, bean, NoOpBatchMetrics.INSTANCE);
    }

    public DisruptorQueue(String queueName, BatchRequestMetaData meta, Object bean, BatchMetrics metrics) {
        this(queueName, meta, bean, metrics, false);
    }

    /**
     * @param virtualThreads
     *            批量方法是否在虚拟线程上执行——由 {@code BatchRegistry} 依据 {@code spring.threads.virtual.enabled} + JDK 21+（
     *            {@code BizPoolRegistry#usesVirtualThreads()}）判定。为 {@code true} 时 只把 bizExecutor 的线程工厂换成虚拟线程（线程名
     *            {@code batch-virtual-*}）； 池的并发上限 {@code consumerSize}、无排队策略与 CallerRunsPolicy 背压语义不变。
     */
    public DisruptorQueue(String queueName, BatchRequestMetaData meta, Object bean, BatchMetrics metrics,
            boolean virtualThreads) {
        this.queueName = queueName;
        this.metrics = metrics != null ? metrics : NoOpBatchMetrics.INSTANCE;
        int configuredSize = meta.ringBufferSize();
        int size = normalizeRingBufferSize(configuredSize);
        // 只对「归一化前后不一致」告警：normalizeRingBufferSize 的返回值恒在 [64, 262144]，
        // 若判断归一化后的 size 则条件恒假、告警永不出现（配置错误会被静默接受）。
        if (configuredSize != size) {
            log.warn("Queue [{}] ringBufferSize={} normalized to {} (expected a power of two within [64, 262144])",
                    queueName, configuredSize, size);
        }
        int consumerSize = meta.consumerSize();

        this.disruptor = new Disruptor<>(BatchEvent::new, size, r -> {
            Thread t = new Thread(r, "batch-disruptor-" + queueName);
            t.setDaemon(false);
            return t;
        }, ProducerType.MULTI, WaitStrategyFactory.create(meta.waitStrategy()));

        boolean useVirtualThreads = virtualThreads && VirtualThreadSupport.isAvailable();
        if (virtualThreads && !useVirtualThreads) {
            // 防御：调用方本应只在 JDK 21+ 传 true（BizPoolRegistry#usesVirtualThreads），
            // 若被误传则回落平台池而不是启动失败。
            log.warn("Queue [{}] requested virtual threads but JDK 21+ is unavailable — "
                    + "falling back to platform pool", queueName);
        }

        // Dedicated business thread pool — SynchronousQueue + CallerRunsPolicy
        // Pool full → consumer thread executes the task → consumer blocks → RingBuffer backpressure
        //
        // 虚拟线程模式只替换线程工厂（batch-virtual-*）：池大小、排队与背压语义完全不变——
        // consumerSize 在两种模式下都是最大并发处理线程数，满员时同样由消费者线程自行执行。
        ThreadFactory bizThreadFactory = useVirtualThreads
                ? VirtualThreadSupport.newThreadFactory("batch-virtual-" + queueName + "-")
                : platformThreadFactory(queueName);
        this.bizExecutor = new ThreadPoolExecutor(0, consumerSize, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
                bizThreadFactory, (r, executor) -> {
                    if (!executor.isShutdown()) {
                        r.run(); // CallerRunsPolicy — consumer thread executes directly
                    }
                });

        this.batchHandler = new BufferingBatchHandler(bizExecutor, meta, bean, meta.maxBatchSize(), metrics);
        this.disruptor.handleEventsWith(this.batchHandler);
        this.disruptor.handleExceptionsWith(new BatchExceptionHandler(queueName));
        this.disruptor.start();

        this.ringBuffer = disruptor.getRingBuffer();
        this.backpressure = meta.backpressure();

        log.info("Batch disruptor [{}] started: ringBufferSize={}, waitStrategy={}, consumerSize={}, bizExecutor={}",
                queueName, size, meta.waitStrategy(), consumerSize,
                useVirtualThreads ? "virtual-threads" : "platform-pool");
    }

    public void enqueue(BatchRequest<?> request) {
        // 已知边界（Tier 1 评审确认，保持零锁热路径）：halted 检查与 publish 之间存在纳秒级
        // 竞态窗口——enqueue 读到 halted=false 后，shutdown 恰好完成 disruptor.shutdown()
        // （drain 全部已发布事件 + 停消费线程），随后本线程才发布 → 事件滞留环形缓冲无人
        // 消费，该请求由 DeferredResult 30s 超时兜底。仅停机瞬间概率性发生，可接受。
        // 如需彻底关闭窗口：用 enqueueLock 互斥「检查 halted → publish」与 shutdown 的
        // halted 设置（synchronized 包裹），代价是每次 enqueue 一次 monitor 进入/退出。
        if (halted.get()) {
            metrics.recordEnqueue(queueName, false);
            // 停机期间零星请求不走 Disruptor，直接调 batch 方法处理。
            // 注意：此处同步执行业务，调用线程即生产者。若生产者是 EventLoop
            // （pool.default-execute-mode=eventloop 或虚拟线程零切换路径），慢批量方法会
            // 阻塞该 EventLoop 上的其他请求。窗口仅限停机瞬间，故不改成"先提交 bizExecutor"
            // ——彼时 bizExecutor 可能已 shutdown，提交会被 CallerRunsPolicy 静默丢弃，
            // 反而让请求只能等 30s 超时。
            batchHandler.processDirectly(List.of(request));
            return;
        }
        switch (backpressure) {
            case DROP:
                if (!ringBuffer.tryPublishEvent(translator, request)) {
                    metrics.recordDrop(queueName);
                    if (request.isCompleted()) {
                        log.warn("Queue [{}] drop failed — request already completed (likely timed out), "
                                + "client will not receive overflow signal", queueName);
                    } else {
                        request.setError(new BatchOverflowException(queueName));
                    }
                } else {
                    metrics.recordEnqueue(queueName, true);
                }
                break;
            case THROW:
                if (!ringBuffer.tryPublishEvent(translator, request)) {
                    metrics.recordOverflow(queueName);
                    throw new BatchOverflowException(queueName);
                }
                metrics.recordEnqueue(queueName, true);
                break;
            case BLOCK:
            default:
                ringBuffer.publishEvent(translator, request);
                metrics.recordEnqueue(queueName, true);
                break;
        }
    }

    public String queueName() {
        return queueName;
    }

    /** 平台线程工厂（线程名 {@code batch-worker-<queue>-*}）。 */
    private static ThreadFactory platformThreadFactory(String queueName) {
        return r -> {
            Thread t = new Thread(r, "batch-worker-" + queueName);
            t.setDaemon(false);
            return t;
        };
    }

    /**
     * Returns the number of remaining (free) slots in the ring buffer.
     */
    public int remainingCapacity() {
        return (int) ringBuffer.remainingCapacity();
    }

    /**
     * Returns the total capacity of the ring buffer.
     */
    public int bufferSize() {
        return ringBuffer.getBufferSize();
    }

    /**
     * 三步优雅停机：先 drain RingBuffer，再冲刷 handler 残余 buffer，最后等待在途批处理完成。
     * <p>
     * 最坏耗时可叠加到 60s 以上：Step 2 的 {@code flushRemaining()} 会向 bizExecutor 提交任务， 若池已满员则 {@code CallerRunsPolicy} 让<b>调用
     * shutdown 的线程</b>（通常是 Spring 容器关闭线程）同步执行整批业务； Step 3 再最多等待 {@code awaitTermination(30s)}。停机慢时应优先怀疑批量方法本身耗时，而非
     * Disruptor。
     * </p>
     */
    public void shutdown() {
        if (!halted.compareAndSet(false, true))
            return;

        // Step 1: Gracefully drain the Disruptor RingBuffer — consume all published events
        try {
            disruptor.shutdown();
        } catch (Exception e) {
            log.warn("Error draining disruptor for queue [{}], forcing halt", queueName, e);
            try {
                disruptor.halt();
            } catch (Exception ignored) {
            }
        }

        // Step 2: Flush any remaining buffer in the batch handler
        // After disruptor.shutdown(), the event processor has stopped, so onEvent()
        // will no longer be called. The flush covers edge cases where the last
        // batch was not fully flushed before shutdown.
        try {
            batchHandler.flushRemaining();
        } catch (Exception e) {
            log.warn("Error flushing remaining batch for queue [{}]", queueName, e);
        } finally {
            // 关窗必须在 flushRemaining 之后：否则迟到的定时任务可能把刚冲刷掉的请求
            // 再次取走（buffer 已空则无害，但顺序反了会出现「取走后又来一个定时任务」的竞态）。
            batchHandler.closeWindow();
        }

        // Step 3: Wait for in-flight batch processing to complete
        bizExecutor.shutdown();
        try {
            if (!bizExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Biz executor for queue [{}] did not terminate in 30s, forcing shutdown", queueName);
                bizExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            bizExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("Batch disruptor [{}] shut down gracefully", queueName);
    }

    static int normalizeRingBufferSize(int size) {
        if (size <= 0)
            return 4096;
        if (size < 64)
            return 64;
        int result = Integer.highestOneBit(size);
        if (result < size)
            result <<= 1;
        // 钳制到安全上限 2^18（262144 槽位，预分配约 6MB）。
        // 左移溢出（result < 0）或超过上限时回退到最大合理容量。
        return result > 0 && result <= (1 << 18) ? result : 1 << 18;
    }
}
