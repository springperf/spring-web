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
                .tags("method", "GET", "path", "/api/users/{id}", "status", "200").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void recordRequest_reuseTimer() {
        metrics.recordRequest("GET", "/api/users/{id}", 200, 1_000_000L);
        metrics.recordRequest("GET", "/api/users/{id}", 200, 2_000_000L);

        Timer timer = meterRegistry.find("dispatcher.request.duration")
                .tags("method", "GET", "path", "/api/users/{id}", "status", "200").timer();
        assertNotNull(timer);
        assertEquals(2, timer.count());
    }

    @Test
    void recordRequest_pathPatternNull_usesEmptyString() {
        metrics.recordRequest("POST", null, 404, 500_000L);

        Timer timer = meterRegistry.find("dispatcher.request.duration")
                .tags("method", "POST", "path", "", "status", "404").timer();
        assertNotNull(timer);
    }

    @Test
    void recordException_createsCounter() {
        metrics.recordException("java.lang.RuntimeException", true);

        Counter counter = meterRegistry.find("dispatcher.exception")
                .tags("type", "java.lang.RuntimeException", "resolved", "true").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.0);
    }

    @Test
    void recordException_notResolved_counterIncremented() {
        metrics.recordException("java.lang.RuntimeException", false);

        Counter counter = meterRegistry.find("dispatcher.exception")
                .tags("type", "java.lang.RuntimeException", "resolved", "false").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.0);
    }

    @Test
    void recordException_reuseCounter() {
        metrics.recordException("java.lang.RuntimeException", false);
        metrics.recordException("java.lang.RuntimeException", false);

        Counter counter = meterRegistry.find("dispatcher.exception")
                .tags("type", "java.lang.RuntimeException", "resolved", "false").counter();
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
                .tags("method", "GET", "path", "/api/x", "status", "200").timer();
        assertNotNull(timer);
        assertEquals(threads * perThread, timer.count());
        assertEquals(1,
                meterRegistry.getMeters().stream()
                        .filter(m -> "dispatcher.request.duration".equals(m.getId().getName())).count(),
                "同 key 并发只允许注册一个 Timer");
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
                .tags("type", "java.lang.IllegalStateException", "resolved", "true").counter();
        assertNotNull(counter);
        assertEquals(threads * perThread, counter.count(), 0.0);
        assertEquals(1, meterRegistry.getMeters().stream()
                .filter(m -> "dispatcher.exception".equals(m.getId().getName())).count(), "同 key 并发只允许注册一个 Counter");
    }

    @Test
    void registerPoolGauges_isIdempotent() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            metrics.registerPoolGauges("myPool", executor);
            int afterFirst = meterRegistry.getMeters().size();

            // 重复调用此前会为同一池重复注册 gauge（读数叠加），也白白增加注册开销
            metrics.registerPoolGauges("myPool", executor);
            metrics.registerPoolGauges("myPool", executor);

            assertEquals(afterFirst, meterRegistry.getMeters().size(), "重复注册同一池不应新增 meter");
            assertEquals(1, meterRegistry.getMeters().stream()
                    .filter(m -> "pool.myPool.active.threads".equals(m.getId().getName())).count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recordRequest_whenCacheOverflows_stillRecordsIntoFallbackTimer() {
        // 灌入超过上限的不同 path，模拟 pathPattern 解析失败后回落到实际 URI 导致的高基数场景
        for (int i = 0; i < MicrometerWebMetrics.MAX_CACHED_KEYS + 50; i++) {
            metrics.recordRequest("GET", "/dynamic/" + i, 200, 1_000_000L);
        }

        // 超限后新组合不再注册独立 meter（缓存有界）
        long distinctPathMeters = meterRegistry.getMeters().stream()
                .filter(m -> "dispatcher.request.duration".equals(m.getId().getName()))
                .filter(m -> !MicrometerWebMetrics.OVERFLOW_PATH.equals(m.getId().getTag("path"))).count();
        assertTrue(distinctPathMeters <= MicrometerWebMetrics.MAX_CACHED_KEYS,
                "独立 meter 数量应有上界，实际 " + distinctPathMeters);

        // 但请求不能被静默丢弃：仍可通过 overflow 桶观测到
        Timer overflow = meterRegistry.find("dispatcher.request.duration")
                .tag("path", MicrometerWebMetrics.OVERFLOW_PATH).timer();
        assertTrue(overflow.count() > 0, "超限后的请求仍应被记录，否则完全不可观测");
    }

    @Test
    void recordRequest_overflowBucketsAreKeyedByMethodAndStatus() {
        // 先灌满缓存
        for (int i = 0; i < MicrometerWebMetrics.MAX_CACHED_KEYS + 10; i++) {
            metrics.recordRequest("GET", "/fill/" + i, 200, 1_000_000L);
        }

        // 溢出阶段：不同 method/status 的请求必须落到各自的桶，而不是共享一个"首个请求"的 tag 组合
        metrics.recordRequest("POST", "/overflow/a", 500, 5_000_000L);
        metrics.recordRequest("DELETE", "/overflow/b", 404, 7_000_000L);

        Timer post500 = meterRegistry.find("dispatcher.request.duration")
                .tag("path", MicrometerWebMetrics.OVERFLOW_PATH).tag("method", "POST").tag("status", "500").timer();
        Timer delete404 = meterRegistry.find("dispatcher.request.duration")
                .tag("path", MicrometerWebMetrics.OVERFLOW_PATH).tag("method", "DELETE").tag("status", "404").timer();

        assertNotNull(post500, "应存在 POST/500 的独立溢出桶");
        assertNotNull(delete404, "应存在 DELETE/404 的独立溢出桶");
        assertEquals(5_000_000L, post500.totalTime(TimeUnit.NANOSECONDS), 0.001,
                "POST 的耗时应记在 POST 桶，不得串入其它 method");
        assertEquals(7_000_000L, delete404.totalTime(TimeUnit.NANOSECONDS), 0.001,
                "DELETE 的耗时应记在 DELETE 桶，不得串入其它 method");
    }

    @Test
    void registerPoolGauges_createsGauges() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100));

        metrics.registerPoolGauges("myPool", executor);

        assertNotNull(meterRegistry.find("pool.myPool.active.threads").gauge());
        assertNotNull(meterRegistry.find("pool.myPool.queue.size").gauge());
        assertNotNull(meterRegistry.find("pool.myPool.completed.tasks").gauge());
    }

    @Test
    void registerPoolGauges_gaugeValuesReflectExecutorState() {
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(100);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, queue);

        metrics.registerPoolGauges("myPool", executor);

        assertEquals(0.0, meterRegistry.find("pool.myPool.active.threads").gauge().value(), 0.0);
        assertEquals(0.0, meterRegistry.find("pool.myPool.queue.size").gauge().value(), 0.0);
        assertEquals(0.0, meterRegistry.find("pool.myPool.completed.tasks").gauge().value(), 0.0);
    }
}
