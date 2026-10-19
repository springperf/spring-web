package io.springperf.web.batch.queue;

import com.lmax.disruptor.EventHandler;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import io.springperf.web.batch.metrics.BatchMetrics;
import io.springperf.web.batch.metrics.NoOpBatchMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class BufferingBatchHandler implements EventHandler<BatchEvent> {

    private static final Logger log = LoggerFactory.getLogger(BufferingBatchHandler.class);

    private final Executor executor;
    private final BatchRequestMetaData meta;
    private final Object bean;
    private final int maxBatchSize;
    private final BatchMetrics metrics;

    /**
     * 攒批时间窗口（纳秒），0 表示关闭。
     *
     * @see io.springperf.web.batch.annotation.BatchMapping#batchWindowMillis()
     */
    private final long windowNanos;

    /**
     * buffer 的锁。关闭窗口时 {@link #onEvent} 是唯一的访问者（Disruptor 消费者单线程）， 本锁不会被争用；开启窗口后定时器线程也会进来取批，才需要互斥。
     * <p>
     * 持锁时间只有一次 {@code add} 或一次引用交换（数十纳秒），远小于窗口周期，不会成为瓶颈。
     * </p>
     */
    private final Object bufferLock = new Object();

    /** 当前攒批缓冲。仅在 {@link #bufferLock} 内读写引用本身；元素 add 同样在锁内完成。 */
    private List<BatchRequest<?>> buffer = new ArrayList<>();

    /** 窗口调度器；开启窗口且本队列是首个使用者时由 {@link #ensureScheduler()} 创建。 */
    private static volatile ScheduledExecutorService windowScheduler;

    /** 已注册的窗口任务数，用于在归零时关停共享调度器。 */
    private static final AtomicLong WINDOW_TASKS = new AtomicLong();

    private volatile boolean windowClosed;

    public BufferingBatchHandler(Executor executor, BatchRequestMetaData meta, Object bean, int maxBatchSize) {
        this(executor, meta, bean, maxBatchSize, NoOpBatchMetrics.INSTANCE);
    }

    public BufferingBatchHandler(Executor executor, BatchRequestMetaData meta, Object bean, int maxBatchSize,
            BatchMetrics metrics) {
        this.executor = executor;
        this.meta = meta;
        this.bean = bean;
        this.maxBatchSize = maxBatchSize;
        this.metrics = metrics != null ? metrics : NoOpBatchMetrics.INSTANCE;
        this.windowNanos = resolveWindowNanos(meta);
    }

    private static long resolveWindowNanos(BatchRequestMetaData meta) {
        try {
            return meta.batchWindowMillis() <= 0 ? 0L : meta.batchWindowMillis() * 1_000_000L;
        } catch (AbstractMethodError | NoSuchMethodError e) {
            // 兼容未同步的旧版 BatchRequestMetaData 实现（如外部自定义子类）
            return 0L;
        }
    }

    @Override
    public void onEvent(BatchEvent event, long sequence, boolean endOfBatch) {
        BatchRequest<?> request = event.request();
        // 立即释放槽位引用：BatchEvent 由 RingBuffer 预分配并复用，若不清理，已完成的
        // BatchRequest（连同其 DeferredResult 内部结构与用户载荷引用）会被钉在槽位上，
        // 直到 sequence 推进超过 bufferSize 才被覆盖——写入突发后的空闲期造成无谓堆占用。
        // 取引用与 clear 都在消费者单线程内完成，不存在可见性问题。
        event.clear();

        List<BatchRequest<?>> toFlush = null;
        synchronized (bufferLock) {
            buffer.add(request);
            if (maxBatchSize > 0 && buffer.size() >= maxBatchSize) {
                // 攒满立即刷：窗口只决定「最少等多久」，不阻止提前刷
                toFlush = takeBufferLocked();
            } else if (windowNanos > 0) {
                // 窗口开启：不响应 endOfBatch，把刷出时机交给定时器（或后续攒满）。
                // 这是关键——endOfBatch 在窗口模式下几乎恒为 true（消费者追得上生产者），
                // 若继续响应它，窗口就永远等不到第二个请求。
                if (buffer.size() == 1) {
                    startWindowLocked();
                }
            } else if (endOfBatch) {
                toFlush = takeBufferLocked();
            }
        }
        if (toFlush != null) {
            submit(toFlush);
        }
    }

    /**
     * 取出当前缓冲并替换为新 list。调用方必须持有 {@link #bufferLock}。
     */
    private List<BatchRequest<?>> takeBufferLocked() {
        if (buffer.isEmpty()) {
            return null;
        }
        List<BatchRequest<?>> batch = buffer;
        buffer = new ArrayList<>();
        return batch;
    }

    private void submit(List<BatchRequest<?>> batch) {
        executor.execute(() -> processBatch(batch));
    }

    /**
     * 启动/续期窗口调度。仅在 buffer 从空变为 1 时调用，避免每来一个请求就排一次任务。
     */
    private void startWindowLocked() {
        if (windowClosed) {
            return;
        }
        ensureScheduler().schedule(this::flushOnWindow, windowNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 窗口到期：把累积的整批提交。由调度线程调用，与 {@link #onEvent} 通过 {@link #bufferLock} 互斥。
     */
    void flushOnWindow() {
        List<BatchRequest<?>> toFlush;
        synchronized (bufferLock) {
            toFlush = takeBufferLocked();
        }
        if (toFlush != null) {
            submit(toFlush);
        }
    }

    /**
     * 优雅停机时冲刷残余缓冲。此时 Disruptor 消费者已停止，不会与 {@link #onEvent} 交叉； 但窗口定时器可能仍在跑，故仍走锁。
     */
    public void flushRemaining() {
        List<BatchRequest<?>> toFlush;
        synchronized (bufferLock) {
            toFlush = takeBufferLocked();
        }
        if (toFlush != null) {
            submit(toFlush);
        }
    }

    /** 包级可见：优雅停机兜底时由 DisruptorQueue 直接调用 */
    void processDirectly(List<BatchRequest<?>> batch) {
        processBatch(batch);
    }

    /**
     * 关闭窗口调度。由 {@link DisruptorQueue#shutdown()} 在 flushRemaining 之后调用， 确保不会有迟到的定时任务把已停机的请求重新提交。
     */
    void closeWindow() {
        synchronized (bufferLock) {
            windowClosed = true;
        }
        if (WINDOW_TASKS.decrementAndGet() <= 0) {
            ScheduledExecutorService scheduler = windowScheduler;
            if (scheduler != null) {
                windowScheduler = null;
                scheduler.shutdownNow();
            }
        }
    }

    /** 是否开启了攒批窗口（测试与诊断用）。 */
    public boolean isWindowEnabled() {
        return windowNanos > 0;
    }

    private static synchronized ScheduledExecutorService ensureScheduler() {
        ScheduledExecutorService scheduler = windowScheduler;
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    // daemon：窗口调度是辅助线程，不应拖住 JVM 退出
                    Thread t = new Thread(r, "batch-window");
                    t.setDaemon(true);
                    return t;
                }
            });
            windowScheduler = scheduler;
        }
        WINDOW_TASKS.incrementAndGet();
        return scheduler;
    }

    private void processBatch(List<BatchRequest<?>> batch) {
        long start = System.nanoTime();
        int batchSize = batch.size();
        boolean success = false;
        try {
            Object[] args = new Object[] { batch };
            meta.batchMethod().invoke(bean, args);
            success = true;
            metrics.recordRequestCompleted(meta.queueName(), batchSize);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            handleBatchError(batch, cause);
        } catch (Throwable e) {
            handleBatchError(batch, e);
        } finally {
            metrics.recordBatchProcessed(meta.queueName(), batchSize, System.nanoTime() - start, success);
        }
    }

    private void handleBatchError(List<BatchRequest<?>> batch, Throwable cause) {
        log.error("Error processing batch of {} requests from queue [{}]", batch.size(), meta.queueName(), cause);
        for (BatchRequest<?> req : batch) {
            if (!req.isCompleted()) {
                req.setError(cause);
            }
        }
    }
}
