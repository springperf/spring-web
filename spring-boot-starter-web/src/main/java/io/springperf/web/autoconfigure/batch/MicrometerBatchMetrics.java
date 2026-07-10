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
 *   <li>{@code batch.enqueue.total} — Counter, total enqueue attempts</li>
 *   <li>{@code batch.enqueue.dropped} — Counter, requests dropped due to backpressure</li>
 *   <li>{@code batch.enqueue.overflow} — Counter, overflow exceptions thrown</li>
 *   <li>{@code batch.process.duration} — Timer, batch processing duration</li>
 *   <li>{@code batch.process.batch.size} — DistributionSummary, batch size distribution</li>
 *   <li>{@code batch.process.requests} — Counter, individual requests completed via batch</li>
 *   <li>{@code batch.queue.remaining} — Gauge, ring buffer remaining capacity</li>
 *   <li>{@code batch.queue.capacity} — Gauge, ring buffer total capacity</li>
 * </ul>
 * </p>
 */
public class MicrometerBatchMetrics implements BatchMetrics {

    private static final String TAG_QUEUE = "queue";

    private final MeterRegistry meterRegistry;

    /** Per-queue counters, cached at first access. */
    private final Map<String, Counter> enqueueCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> dropCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> overflowCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> requestCounters = new ConcurrentHashMap<>();

    /** Per-queue timers and summaries, cached at first access. */
    private final Map<String, Timer> durationTimers = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> batchSizeSummaries = new ConcurrentHashMap<>();

    /** Per-queue gauge backing values. */
    private final Map<String, AtomicInteger> remainingCapacities = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> capacityTotals = new ConcurrentHashMap<>();

    public MicrometerBatchMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordEnqueue(String queueName, boolean success) {
        counter(enqueueCounters, "batch.enqueue.total", queueName).increment();
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
        timer(durationTimers, "batch.process.duration", queueName)
                .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        summary(batchSizeSummaries, "batch.process.batch.size", queueName)
                .record(batchSize);
    }

    @Override
    public void recordRequestCompleted(String queueName, int count) {
        counter(requestCounters, "batch.process.requests", queueName).increment(count);
    }

    @Override
    public void reportQueueCapacity(String queueName, int remaining, int total) {
        AtomicInteger cap = remainingCapacities.computeIfAbsent(queueName, k -> {
            AtomicInteger v = new AtomicInteger(remaining);
            Gauge.builder("batch.queue.remaining", v, AtomicInteger::get)
                    .tag(TAG_QUEUE, queueName)
                    .strongReference(true)
                    .register(meterRegistry);
            return v;
        });
        cap.set(remaining);

        capacityTotals.computeIfAbsent(queueName, k -> {
            AtomicLong v = new AtomicLong(total);
            Gauge.builder("batch.queue.capacity", v, AtomicLong::get)
                    .tag(TAG_QUEUE, queueName)
                    .strongReference(true)
                    .register(meterRegistry);
            return v;
        });
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

    private Timer timer(Map<String, Timer> cache, String name, String queueName) {
        return cache.computeIfAbsent(queueName, k -> {
            return Timer.builder(name).tag(TAG_QUEUE, queueName).register(meterRegistry);
        });
    }

    private DistributionSummary summary(Map<String, DistributionSummary> cache, String name, String queueName) {
        return cache.computeIfAbsent(queueName, k -> {
            return DistributionSummary.builder(name).tag(TAG_QUEUE, queueName).register(meterRegistry);
        });
    }
}