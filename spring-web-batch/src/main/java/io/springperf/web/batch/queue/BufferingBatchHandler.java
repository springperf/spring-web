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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

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

    /**
     * 窗口调度器；全进程共享一个 daemon 线程，仅在 {@link #SCHEDULER_LOCK} 内读写。
     */
    private static volatile ScheduledExecutorService windowScheduler;

    /** {@link #windowScheduler} 当前实例上的登记数（只在 {@link #SCHEDULER_LOCK} 内变更）。 */
    private static long windowTaskCount;

    /**
     * 保护「获取/创建调度器 + 登记/注销」的静态锁。
     * <p>
     * 登记与注销必须共用同一把锁：早期版本用独立的 AtomicLong 计数、关闭动作不在锁内， 存在「关掉别的队列刚重建的调度器」以及
     * {@code schedule()} 抛 {@code RejectedExecutionException} 冒到消费者的竞态。
     * </p>
     */
    private static final Object SCHEDULER_LOCK = new Object();

    /**
     * 本 handler 已登记到哪个调度器实例；{@code null} 表示未登记（无窗口，或还没收到过事件）。
     * <p>
     * 用「登记在哪个实例上」而不是一个计数器，是为了让关闭动作只减自己那一份：无窗口队列即使没有登记过， 停机时也不会误减其他队列的登记数。
     * 调度器被重建后旧实例的登记自动失效（{@link #windowTaskCount} 随重建归零）。
     * </p>
     */
    private volatile ScheduledExecutorService registeredScheduler;

    private volatile boolean windowClosed;

    /** 每批处理结束后的回调（用于上报 RingBuffer 剩余容量），可为 {@code null}。 */
    private final Runnable afterBatchAction;

    public BufferingBatchHandler(Executor executor, BatchRequestMetaData meta, Object bean, int maxBatchSize) {
        this(executor, meta, bean, maxBatchSize, NoOpBatchMetrics.INSTANCE, null);
    }

    public BufferingBatchHandler(Executor executor, BatchRequestMetaData meta, Object bean, int maxBatchSize,
            BatchMetrics metrics) {
        this(executor, meta, bean, maxBatchSize, metrics, null);
    }

    public BufferingBatchHandler(Executor executor, BatchRequestMetaData meta, Object bean, int maxBatchSize,
            BatchMetrics metrics, Runnable afterBatchAction) {
        this.executor = executor;
        this.meta = meta;
        this.bean = bean;
        this.maxBatchSize = maxBatchSize;
        this.metrics = metrics != null ? metrics : NoOpBatchMetrics.INSTANCE;
        this.afterBatchAction = afterBatchAction;
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
        boolean flushNow = false;
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
                    flushNow = scheduleWindowLocked();
                }
            } else if (endOfBatch) {
                toFlush = takeBufferLocked();
            }
        }
        if (flushNow) {
            // 窗口任务被拒（调度器正在关闭）：立即刷出，别让这批请求挂到 DeferredResult 超时。
            // 必须在 bufferLock 之外执行——submit 可能触发 CallerRunsPolicy 同步执行业务 IO。
            flushOnWindow();
        } else if (toFlush != null) {
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
        try {
            executor.execute(() -> processBatch(batch));
        } catch (RejectedExecutionException e) {
            // 业务池已在停机中：批次不能静默消失，否则批内请求只能等 DeferredResult 超时。
            log.warn("Batch executor rejected {} requests from queue [{}] - failing them explicitly", batch.size(),
                    meta.queueName(), e);
            failRequests(batch, e);
        }
    }

    /**
     * 启动窗口调度。仅在 buffer 从空变为 1 时调用，避免每来一个请求就排一次任务。
     *
     * @return {@code true} 表示任务被调度器拒绝（正在关闭），调用方应立即刷出本批
     */
    private boolean scheduleWindowLocked() {
        if (windowClosed) {
            return false;
        }
        try {
            ensureScheduler().schedule(this::flushOnWindow, windowNanos, TimeUnit.NANOSECONDS);
            return false;
        } catch (RejectedExecutionException e) {
            log.warn("Window scheduler rejected task for queue [{}] - flushing immediately", meta.queueName(), e);
            return true;
        }
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
        synchronized (SCHEDULER_LOCK) {
            ScheduledExecutorService scheduler = registeredScheduler;
            registeredScheduler = null;
            if (scheduler == null || scheduler != windowScheduler) {
                // 从未登记（无窗口或没收到过事件），或该实例已被重建：不碰当前调度器的登记数
                return;
            }
            if (--windowTaskCount <= 0) {
                windowTaskCount = 0;
                windowScheduler = null;
                scheduler.shutdownNow();
            }
        }
    }

    /** 是否开启了攒批窗口（测试与诊断用）。 */
    public boolean isWindowEnabled() {
        return windowNanos > 0;
    }

    /**
     * 获取（必要时创建）共享调度器，并把本 handler 登记到该实例上——同一 handler 只登记一次。
     * <p>
     * 与 {@link #closeWindow()} 共用 {@link #SCHEDULER_LOCK}：获取/创建、登记、注销、关停全部串行化， 避免「关掉别的队列刚重建的实例」以及拿到已关闭实例去
     * {@code schedule()} 的竞态。
     * </p>
     */
    private ScheduledExecutorService ensureScheduler() {
        synchronized (SCHEDULER_LOCK) {
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
                // 新实例从零开始计数：旧实例的登记随其关闭一并失效
                windowTaskCount = 0;
            }
            if (registeredScheduler != scheduler) {
                registeredScheduler = scheduler;
                windowTaskCount++;
            }
            return scheduler;
        }
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
            // 先给每个请求一个结论，再做指标上报：上报实现抛异常时也不能让请求退化为「等超时」。
            // 批量方法「正常返回但漏调 setResult」是最常见的使用错误：不兜底的话这批请求既没有响应
            // 也没有日志，只能静默挂满 DeferredResult 超时（默认 30s）。
            failUncompleted(batch);
            metrics.recordBatchProcessed(meta.queueName(), batchSize, System.nanoTime() - start, success);
            reportCapacity();
        }
    }

    private void handleBatchError(List<BatchRequest<?>> batch, Throwable cause) {
        log.error("Error processing batch of {} requests from queue [{}]", batch.size(), meta.queueName(), cause);
        failRequests(batch, cause);
    }

    /** 以指定异常完成批内所有未完成的请求；已完成（含超时、重复设置）的请求保持原结果。 */
    private void failRequests(List<BatchRequest<?>> batch, Throwable cause) {
        for (BatchRequest<?> req : batch) {
            if (!req.isCompleted()) {
                req.setError(cause);
            }
        }
    }

    /** 批量方法返回后仍未完成的请求必须以显式错误收尾，避免客户端悬停到超时。 */
    private void failUncompleted(List<BatchRequest<?>> batch) {
        int unfinished = 0;
        IllegalStateException error = null;
        for (BatchRequest<?> req : batch) {
            if (req.isCompleted()) {
                continue;
            }
            if (error == null) {
                error = new IllegalStateException("Batch method [" + meta.batchMethod().getName()
                        + "] returned without completing request from queue [" + meta.queueName() + "]");
            }
            req.setError(error);
            unfinished++;
        }
        if (unfinished > 0) {
            log.warn("Batch method [{}] completed {} of {} requests from queue [{}] - "
                    + "the remaining ones failed explicitly instead of hanging until timeout", meta.batchMethod().getName(),
                    batch.size() - unfinished, batch.size(), meta.queueName());
        }
    }

    /**
     * 每批处理结束后上报 RingBuffer 剩余容量。
     * <p>
     * 采样点选在这里而不是 {@code enqueue}：容量是「水位」类指标，批次频率（远低于请求频率）足够， 又不必在生产者热路径上加计数或原子操作。
     * </p>
     */
    private void reportCapacity() {
        Runnable action = afterBatchAction;
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException e) {
            // 指标上报失败不得影响业务结果
            log.warn("Failed to report queue capacity for [{}]", meta.queueName(), e);
        }
    }
}
