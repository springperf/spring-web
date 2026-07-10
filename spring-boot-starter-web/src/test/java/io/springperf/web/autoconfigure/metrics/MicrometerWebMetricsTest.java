package io.springperf.web.autoconfigure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MicrometerWebMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MicrometerWebMetrics metrics = new MicrometerWebMetrics(meterRegistry);

    @AfterEach
    void cleanUp() {
        meterRegistry.clear();
    }

    @Test
    void recordRequest_createsTimer() {
        metrics.recordRequest("GET", "/api/users/{id}", 200, 1_000_000L);

        Timer timer = meterRegistry.find("dispatcher.request.duration")
                .tags("method", "GET", "path", "/api/users/{id}", "status", "200")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void recordRequest_reuseTimer() {
        metrics.recordRequest("GET", "/api/users/{id}", 200, 1_000_000L);
        metrics.recordRequest("GET", "/api/users/{id}", 200, 2_000_000L);

        Timer timer = meterRegistry.find("dispatcher.request.duration")
                .tags("method", "GET", "path", "/api/users/{id}", "status", "200")
                .timer();
        assertNotNull(timer);
        assertEquals(2, timer.count());
    }

    @Test
    void recordRequest_pathPatternNull_usesEmptyString() {
        metrics.recordRequest("POST", null, 404, 500_000L);

        Timer timer = meterRegistry.find("dispatcher.request.duration")
                .tags("method", "POST", "path", "", "status", "404")
                .timer();
        assertNotNull(timer);
    }

    @Test
    void recordException_createsCounter() {
        metrics.recordException("java.lang.RuntimeException", true);

        Counter counter = meterRegistry.find("dispatcher.exception")
                .tags("type", "java.lang.RuntimeException", "resolved", "true")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.0);
    }

    @Test
    void recordException_notResolved_counterIncremented() {
        metrics.recordException("java.lang.RuntimeException", false);

        Counter counter = meterRegistry.find("dispatcher.exception")
                .tags("type", "java.lang.RuntimeException", "resolved", "false")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.0);
    }

    @Test
    void recordException_reuseCounter() {
        metrics.recordException("java.lang.RuntimeException", false);
        metrics.recordException("java.lang.RuntimeException", false);

        Counter counter = meterRegistry.find("dispatcher.exception")
                .tags("type", "java.lang.RuntimeException", "resolved", "false")
                .counter();
        assertEquals(2.0, counter.count(), 0.0);
    }

    @Test
    void registerPoolGauges_createsGauges() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100));

        metrics.registerPoolGauges("myPool", executor);

        assertNotNull(meterRegistry.find("pool.myPool.active.threads").gauge());
        assertNotNull(meterRegistry.find("pool.myPool.queue.size").gauge());
        assertNotNull(meterRegistry.find("pool.myPool.completed.tasks").gauge());
    }

    @Test
    void registerPoolGauges_gaugeValuesReflectExecutorState() {
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(100);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS, queue);

        metrics.registerPoolGauges("myPool", executor);

        assertEquals(0.0, meterRegistry.find("pool.myPool.active.threads").gauge().value(), 0.0);
        assertEquals(0.0, meterRegistry.find("pool.myPool.queue.size").gauge().value(), 0.0);
        assertEquals(0.0, meterRegistry.find("pool.myPool.completed.tasks").gauge().value(), 0.0);
    }
}