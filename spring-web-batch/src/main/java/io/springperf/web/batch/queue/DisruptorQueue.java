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
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.SynchronousQueue;
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

    public DisruptorQueue(String queueName,
                          BatchRequestMetaData meta,
                          Object bean) {
        this(queueName, meta, bean, NoOpBatchMetrics.INSTANCE);
    }

    public DisruptorQueue(String queueName,
                          BatchRequestMetaData meta,
                          Object bean,
                          BatchMetrics metrics) {
        this.queueName = queueName;
        this.metrics = metrics != null ? metrics : NoOpBatchMetrics.INSTANCE;
        int size = normalizeRingBufferSize(meta.ringBufferSize());
        int consumerSize = meta.consumerSize();

        if (size < 64 || size > 256 * 1024) {
            log.warn("Queue [{}] ringBufferSize={} is unusual; expected range [64, 262144]", queueName, size);
        }

        this.disruptor = new Disruptor<>(
                BatchEvent::new,
                size,
                r -> {
                    Thread t = new Thread(r, "batch-disruptor-" + queueName);
                    t.setDaemon(false);
                    return t;
                },
                ProducerType.MULTI,
                WaitStrategyFactory.create(meta.waitStrategy())
        );

        // Dedicated business thread pool — SynchronousQueue + CallerRunsPolicy
        // Pool full → consumer thread executes the task → consumer blocks → RingBuffer backpressure
        this.bizExecutor = new ThreadPoolExecutor(
                0, consumerSize,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "batch-worker-" + queueName);
                    t.setDaemon(false);
                    return t;
                },
                (r, executor) -> {
                    if (!executor.isShutdown()) {
                        r.run(); // CallerRunsPolicy — consumer thread executes directly
                    }
                }
        );

        this.batchHandler = new BufferingBatchHandler(bizExecutor, meta, bean, meta.maxBatchSize(), metrics);
        this.disruptor.handleEventsWith(this.batchHandler);
        this.disruptor.handleExceptionsWith(new BatchExceptionHandler(queueName));
        this.disruptor.start();

        this.ringBuffer = disruptor.getRingBuffer();
        this.backpressure = meta.backpressure();

        log.info("Batch disruptor [{}] started: ringBufferSize={}, waitStrategy={}, consumerSize={}",
                queueName, size, meta.waitStrategy(), consumerSize);
    }

    public void enqueue(BatchRequest<?> request) {
        if (halted.get()) {
            metrics.recordEnqueue(queueName, false);
            request.setError(new IllegalStateException("Queue [" + queueName + "] is shutting down"));
            return;
        }
        switch (backpressure) {
            case DROP:
                if (!ringBuffer.tryPublishEvent(translator, request)) {
                    metrics.recordDrop(queueName);
                    if (request.isCompleted()) {
                        log.warn("Queue [{}] drop failed — request already completed (likely timed out), "
                                        + "client will not receive overflow signal",
                                queueName);
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

    public void shutdown() {
        if (!halted.compareAndSet(false, true)) return;

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
        if (size <= 0) return 4096;
        int result = Integer.highestOneBit(size);
        if (result < size) result <<= 1;
        return result > 0 ? result : 4096;
    }
}