package io.springperf.web.autoconfigure.batch;

import io.micrometer.core.instrument.*;
import io.springperf.web.batch.metrics.BatchMetrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer-based {@link BatchMetrics} implementation.
 * <p>
 * Registers the following metrics (all tagged with {@code queue}={@literal <queueName>}):
 * <ul>
 * <li>{@code batch.enqueue.total} — Counter, total enqueue attempts</li>
 * <li>{@code batch.enqueue.rejected} — Counter, enqueue attempts that failed</li>
 * <li>{@code batch.enqueue.dropped} — Counter, requests dropped due to backpressure</li>
 * <li>{@code batch.enqueue.overflow} — Counter, overflow exceptions thrown</li>
 * <li>{@code batch.process.duration} — Timer, batch processing duration (tagged further with {@code outcome})</li>
 * <li>{@code batch.process.batch.size} — DistributionSummary, batch size distribution</li>
 * <li>{@code batch.process.requests} — Counter, individual requests completed via batch</li>
 * <li>{@code batch.queue.remaining} — Gauge, ring buffer remaining capacity</li>
 * <li>{@code batch.queue.capacity} — Gauge, ring buffer total capacity</li>
 * </ul>
 * <p>
 * The per-queue caches use {@code ConcurrentHashMap}, but gauge registration is performed <em>outside</em> any
 * mapping function (see {@link #registerRemainingGauge}): registering while holding a CHM bin lock would call into
 * {@code MeterRegistry} under that lock and widen the lock-ordering surface.
 * </p>
 */
public class MicrometerBatchMetrics implements BatchMetrics {

    private static final String TAG_QUEUE = "queue";
    private static final String TAG_OUTCOME = "outcome";

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FAILURE = "failure";

    private final MeterRegistry meterRegistry;

    /** Per-queue meters, cached at first access and never evicted (cardinality = number of queues). */
    private final Map<String, Counter> enqueueCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> rejectedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> dropCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> overflowCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> requestCounters = new ConcurrentHashMap<>();

    private final Map<String, Timer> successTimers = new ConcurrentHashMap<>();
    private final Map<String, Timer> failureTimers = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> batchSizeSummaries = new ConcurrentHashMap<>();

    /**
     * Per-queue gauge backing values. Registration is deliberately <em>not</em> done inside a
     * {@code ConcurrentHashMap.computeIfAbsent} mapping function: the mapping function would run while holding a CHM
     * bin lock and call into {@code MeterRegistry}, widening the lock-ordering surface. See
     * {@link #registerRemainingGauge} / {@link #registerCapacityGauge} for the placeholder-then-register pattern.
     */
    private final Map<String, AtomicInteger> remainingCapacities = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> capacityTotals = new ConcurrentHashMap<>();

    public MicrometerBatchMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordEnqueue(String queueName, boolean success) {
        counter(enqueueCounters, "batch.enqueue.total", queueName).increment();
        if (!success) {
            // 失败入队此前被完全丢弃：total 计数无法区分「尝试」与「成功」
            counter(rejectedCounters, "batch.enqueue.rejected", queueName).increment();
        }
    }

    @Override
    public void recordDrop(String queueName) {
        counter(dropCounters, "batch.enqueue.dropped", queueName).increment();
    }

    @Override
    public void recordOverflow(String queueName) {
        counter(overflowCounters, "batch.enqueue.overflow", queueName).increment();
    }

    @Override
    public void recordBatchProcessed(String queueName, int batchSize, long durationNanos, boolean success) {
        // 成败分开打 tag：此前 success 形参被忽略，成功与失败的耗时混在同一条时间序列里，
        // 失败批次的耗时（通常更高）会污染延迟指标的分位数。
        Map<String, Timer> cache = success ? successTimers : failureTimers;
        Timer timer = cache.computeIfAbsent(queueName, k -> Timer.builder("batch.process.duration")
                .tags(TAG_QUEUE, queueName, TAG_OUTCOME, success ? OUTCOME_SUCCESS : OUTCOME_FAILURE)
                .register(meterRegistry));
        timer.record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        summary(batchSizeSummaries, "batch.process.batch.size", queueName).record(batchSize);
    }

    @Override
    public void recordRequestCompleted(String queueName, int count) {
        counter(requestCounters, "batch.process.requests", queueName).increment(count);
    }

    @Override
    public void reportQueueCapacity(String queueName, int remaining, int total) {
        AtomicInteger cap = remainingCapacities.get(queueName);
        if (cap == null) {
            cap = registerRemainingGauge(queueName, remaining);
        }
        cap.set(remaining);

        AtomicLong capacity = capacityTotals.get(queueName);
        if (capacity == null) {
            capacity = registerCapacityGauge(queueName, total);
        }
        // total 此前只在首次注册时生效，后续容量变化（动态扩缩）无法反映到 gauge
        capacity.set(total);
    }

    /**
     * 懒注册 remaining gauge。用"外部 map 先占位、注册动作在 CHM 锁外执行"的写法替代直接
     * {@code computeIfAbsent}：后者会在持有 CHM bin 锁时调用 {@code MeterRegistry.register}，
     * 与 MeterRegistry 内部锁形成潜在死锁面。
     */
    private AtomicInteger registerRemainingGauge(String queueName, int remaining) {
        AtomicInteger placeholder = new AtomicInteger(remaining);
        AtomicInteger existing = remainingCapacities.putIfAbsent(queueName, placeholder);
        if (existing != null) {
            return existing;
        }
        // 本线程赢得占位：在锁外完成注册
        Gauge.builder("batch.queue.remaining", placeholder, AtomicInteger::get).tag(TAG_QUEUE, queueName)
                .strongReference(true).register(meterRegistry);
        return placeholder;
    }

    private AtomicLong registerCapacityGauge(String queueName, int total) {
        AtomicLong placeholder = new AtomicLong(total);
        AtomicLong existing = capacityTotals.putIfAbsent(queueName, placeholder);
        if (existing != null) {
            return existing;
        }
        Gauge.builder("batch.queue.capacity", placeholder, AtomicLong::get).tag(TAG_QUEUE, queueName)
                .strongReference(true).register(meterRegistry);
        return placeholder;
    }

    // ------------------------------------------------------------------
    // Cache helpers
    // ------------------------------------------------------------------

    private Counter counter(Map<String, Counter> cache, String name, String queueName) {
        return cache.computeIfAbsent(queueName, k -> {
            // register() is idempotent per MeterRegistry, but we cache to avoid builder allocation
            return Counter.builder(name).tag(TAG_QUEUE, queueName).register(meterRegistry);
        });
    }

    private DistributionSummary summary(Map<String, DistributionSummary> cache, String name, String queueName) {
        return cache.computeIfAbsent(queueName, k -> {
            return DistributionSummary.builder(name).tag(TAG_QUEUE, queueName).register(meterRegistry);
        });
    }
}
