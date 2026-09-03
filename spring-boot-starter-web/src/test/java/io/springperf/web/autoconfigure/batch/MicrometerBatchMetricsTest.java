package io.springperf.web.autoconfigure.batch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link MicrometerBatchMetrics} 各指标的注册与 per-queue 计数（真实逻辑）。
 */
class MicrometerBatchMetricsTest {

    private MeterRegistry registry;
    private MicrometerBatchMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerBatchMetrics(registry);
    }

    @Test
    void recordEnqueue_incrementsTotalCounter() {
        metrics.recordEnqueue("q1", true);
        metrics.recordEnqueue("q1", false);
        metrics.recordEnqueue("q2", true);
        Counter c1 = registry.get("batch.enqueue.total").tag("queue", "q1").counter();
        Counter c2 = registry.get("batch.enqueue.total").tag("queue", "q2").counter();
        assertEquals(2.0, c1.count());
        assertEquals(1.0, c2.count());
    }

    @Test
    void recordDrop_incrementsDroppedCounter() {
        metrics.recordDrop("q1");
        Counter c = registry.get("batch.enqueue.dropped").tag("queue", "q1").counter();
        assertEquals(1.0, c.count());
    }

    @Test
    void recordOverflow_incrementsOverflowCounter() {
        metrics.recordOverflow("q1");
        Counter c = registry.get("batch.enqueue.overflow").tag("queue", "q1").counter();
        assertEquals(1.0, c.count());
    }

    @Test
    void recordBatchProcessed_recordsTimerAndSummary() {
        metrics.recordBatchProcessed("q1", 5, 1_000_000L, true);
        Timer timer = registry.get("batch.process.duration").tag("queue", "q1").timer();
        assertEquals(1, timer.count());
        assertEquals(1_000_000L, timer.totalTime(TimeUnit.NANOSECONDS), 0.001);

        DistributionSummary summary = registry.get("batch.process.batch.size").tag("queue", "q1").summary();
        assertEquals(1, summary.count());
        assertEquals(5.0, summary.totalAmount(), 0.001);
    }

    @Test
    void recordRequestCompleted_incrementsCounterByCount() {
        metrics.recordRequestCompleted("q1", 3);
        Counter c = registry.get("batch.process.requests").tag("queue", "q1").counter();
        assertEquals(3.0, c.count());
    }

    @Test
    void reportQueueCapacity_registersGauges() {
        metrics.reportQueueCapacity("q1", 100, 200);
        Gauge remaining = registry.get("batch.queue.remaining").tag("queue", "q1").gauge();
        Gauge capacity = registry.get("batch.queue.capacity").tag("queue", "q1").gauge();
        assertEquals(100.0, remaining.value(), 0.001);
        assertEquals(200.0, capacity.value(), 0.001);

        // 更新容量应反映到 gauge
        metrics.reportQueueCapacity("q1", 150, 200);
        assertEquals(150.0, registry.get("batch.queue.remaining").tag("queue", "q1").gauge().value(), 0.001);
        // 缓存：已注册的 gauge 不重复
        assertEquals(1, registry.getMeters().stream()
                .filter(m -> "batch.queue.remaining".equals(m.getId().getName())
                        && "q1".equals(m.getId().getTag("queue"))).count());
    }
}