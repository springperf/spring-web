package io.springperf.web.autoconfigure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void recordRequest_concurrentSameKey_registersSingleTimer() throws Exception {
        // 回归 P2 性能组 #6：修复前 get-then-put 非原子，并发同 key 会重复 register，
        // Micrometer 对同名同 tag 二次注册抛异常。computeIfAbsent 保证只注册一次。
        int threads = 8;
        int perThread = 50;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        metrics.recordRequest("GET", "/api/x", 200, 1000L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        ready.await();
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发 recordRequest 未在超时内完成");

        Timer timer = meterRegistry.find("dispatcher.request.duration")
                .tags("method", "GET", "path", "/api/x", "status", "200")
                .timer();
        assertNotNull(timer);
        assertEquals(threads * perThread, timer.count());
        assertEquals(1, meterRegistry.getMeters().stream()
                .filter(m -> "dispatcher.request.duration".equals(m.getId().getName()))
                .count(), "同 key 并发只允许注册一个 Timer");
    }

    @Test
    void recordException_concurrentSameKey_registersSingleCounter() throws Exception {
        int threads = 8;
        int perThread = 50;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        metrics.recordException("java.lang.IllegalStateException", true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        ready.await();
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发 recordException 未在超时内完成");

        Counter counter = meterRegistry.find("dispatcher.exception")
                .tags("type", "java.lang.IllegalStateException", "resolved", "true")
                .counter();
        assertNotNull(counter);
        assertEquals(threads * perThread, counter.count(), 0.0);
        assertEquals(1, meterRegistry.getMeters().stream()
                .filter(m -> "dispatcher.exception".equals(m.getId().getName()))
                .count(), "同 key 并发只允许注册一个 Counter");
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