package io.springperf.web.autoconfigure;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.springperf.web.autoconfigure.batch.MicrometerBatchMetrics;
import io.springperf.web.autoconfigure.metrics.MicrometerWebMetrics;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 指标契约：把「对外的指标名 + 标签键」冻结成一份白名单。
 * <p>
 * 现有测试逐个断言某个指标（{@code MicrometerWebMetricsTest} / {@code MicrometerBatchMetricsTest}），
 * 能发现改名，但发现不了「新增了指标却没写测试」——这份用例补齐这一点：注册表里出现的每个指标都必须在 下面的白名单里，反之亦然。
 * </p>
 * <p>
 * <b>维护约定</b>：新增/改名任何指标或标签时，必须同时改这里的两张表；新增一个记录方法后，也要在本用例中 触发它（否则该方法注册的指标不会被看到，白名单会悄悄少一项）。
 * </p>
 * <p>
 * 只冻结<b>名称与标签键</b>，不冻结标签<b>取值</b>（取值随请求变化，且已有针对性用例覆盖）。 {@code pool.*} 的池名是运行期数据，归并为 {@code pool.{name}} 前缀后再比较。
 * </p>
 */
class MetricContractTest {

    private static final String QUEUE = "orders";

    @Test
    void webMetrics_nameAndTagKeysAreFrozen() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerWebMetrics metrics = new MicrometerWebMetrics(registry);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            metrics.recordRequest("GET", "/api/users/{id}", 200, 1_000_000L);
            metrics.recordRequest("GET", null, 404, 1_000_000L); // path 缺省回落空串，标签键不变
            metrics.recordException("java.lang.RuntimeException", true);
            metrics.recordException("java.lang.IllegalStateException", false);
            metrics.registerPoolGauges("biz", executor);
            metrics.asyncLifecycleStarted();
            metrics.asyncLifecycleCompleted();

            Map<String, Set<String>> expected = Map.of("dispatcher.request.duration",
                    Set.of("method", "path", "status"), "dispatcher.exception", Set.of("type", "resolved"),
                    "dispatcher.async.active.lifecycles", Set.of(), "pool.{name}.active.threads", Set.of(),
                    "pool.{name}.queue.size", Set.of(), "pool.{name}.completed.tasks", Set.of());

            assertEquals(expected, snapshot(registry), "Web 指标集合或标签键发生变化：若为有意变更，请同步更新本白名单与文档");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void batchMetrics_nameAndTagKeysAreFrozen() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBatchMetrics metrics = new MicrometerBatchMetrics(registry);

        metrics.recordEnqueue(QUEUE, true);
        metrics.recordEnqueue(QUEUE, false);
        metrics.recordDrop(QUEUE);
        metrics.recordOverflow(QUEUE);
        metrics.recordBatchProcessed(QUEUE, 10, 1_000_000L, true);
        metrics.recordBatchProcessed(QUEUE, 10, 1_000_000L, false);
        metrics.recordRequestCompleted(QUEUE, 10);
        metrics.reportQueueCapacity(QUEUE, 32, 64);

        Map<String, Set<String>> expected = Map.of("batch.enqueue.total", Set.of("queue"), "batch.enqueue.rejected",
                Set.of("queue"), "batch.enqueue.dropped", Set.of("queue"), "batch.enqueue.overflow", Set.of("queue"),
                "batch.process.duration", Set.of("queue", "outcome"), "batch.process.batch.size", Set.of("queue"),
                "batch.process.requests", Set.of("queue"), "batch.queue.remaining", Set.of("queue"),
                "batch.queue.capacity", Set.of("queue"));

        assertEquals(expected, snapshot(registry), "Batch 指标集合或标签键发生变化：若为有意变更，请同步更新本白名单与文档");
    }

    /**
     * 指标名 → 标签键集合。同名指标的多个 meter（不同标签值）合并；{@code pool.<池名>.*} 归一为 {@code pool.{name}.*}，因为池名是运行期数据而不是契约的一部分。
     */
    private static Map<String, Set<String>> snapshot(SimpleMeterRegistry registry) {
        Map<String, Set<String>> actual = new LinkedHashMap<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName().replaceFirst("^pool\\.[^.]+\\.", "pool.{name}.");
            Set<String> keys = actual.computeIfAbsent(name, k -> new TreeSet<>());
            for (Tag tag : meter.getId().getTags()) {
                keys.add(tag.getKey());
            }
        }
        return actual;
    }
}
