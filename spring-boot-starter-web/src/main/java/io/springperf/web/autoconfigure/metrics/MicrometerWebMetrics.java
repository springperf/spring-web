package io.springperf.web.autoconfigure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.core.metrics.WebMetrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer-based {@link WebMetrics} implementation.
 * <p>
 * Registers the following metrics:
 * <ul>
 * <li>{@code dispatcher.request.duration} — Timer, tagged with {@code method}, {@code path}, {@code status}</li>
 * <li>{@code dispatcher.exception} — Counter, tagged with {@code type}, {@code resolved}</li>
 * <li>{@code pool.{name}.active.threads} — Gauge, active thread count</li>
 * <li>{@code pool.{name}.queue.size} — Gauge, queue size</li>
 * <li>{@code pool.{name}.completed.tasks} — Gauge, completed task count</li>
 * </ul>
 *
 * @since 2.7.0
 */
public class MicrometerWebMetrics extends BaseWebComponent implements WebMetrics {

    private static final String TAG_METHOD = "method";
    private static final String TAG_PATH = "path";
    private static final String TAG_STATUS = "status";
    private static final String TAG_TYPE = "type";
    private static final String TAG_RESOLVED = "resolved";

    /** 超限后落桶时替代 pathPattern 的固定值：聚合成一个可辨识的桶，而非丢弃观测。 */
    static final String OVERFLOW_PATH = "__overflow__";

    /**
     * 每个 tag 维度组合的上限。{@code path} tag 取自 pathPattern，但运行时解析失败时会回落到实际请求 URI， 其基数不受控（含路径参数的 URL 每个取值都是一个新组合）。无上限则缓存与
     * MeterRegistry 中的 meter 会随流量无限增长（内存泄漏）。达到上限后不再缓存新组合，但**仍然记录**到已被淘汰前的既有 meter 之外—— 见 {@link #recordRequest}
     * 的溢出处理说明。
     */
    static final int MAX_CACHED_KEYS = 1024;

    private final MeterRegistry meterRegistry;

    /** Cached exception counters per (type, resolved) combination. */
    private final ConcurrentMap<String, Counter> exceptionCounters = new ConcurrentHashMap<>();

    /** Cached timers per (method, path, status) combination, bounded by {@link #MAX_CACHED_KEYS}. */
    private final ConcurrentMap<String, Timer> requestTimers = new ConcurrentHashMap<>();

    /** 已注册 gauge 标记，避免重复注册的幂等守卫。 */
    private final java.util.Set<String> registeredPoolGauges = ConcurrentHashMap.newKeySet();

    /**
     * 溢出时统一落到的 timer，按 {@code (method, status)} 分桶，保证超限后 latency 仍有观测（不会被静默丢弃）。
     * <p>
     * 必须分桶而不能用单个共享 timer：{@code path} 是唯一高基数的 tag，去掉它之后 {@code (method, status)} 的 基数完全可控（方法数 × 状态码数）。早期实现只注册一个共享 timer
     * 并沿用**首个**溢出请求的 method/status tag， 导致后续其它 method/status 的请求被计到错误的桶里（例如 GET 的耗时混进 POST 序列）。
     * </p>
     * <p>
     * 桶数量上界 = {@link #MAX_CACHED_KEYS}，与主缓存同级，仍是有界的。
     * </p>
     */
    private final ConcurrentMap<String, Timer> overflowTimers = new ConcurrentHashMap<>();

    public MicrometerWebMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordRequest(String method, String pathPattern, int statusCode, long durationNanos) {
        Timer timer = requestTimers.get(buildRequestKey(method, pathPattern, statusCode));
        if (timer == null) {
            timer = registerRequestTimer(method, pathPattern, statusCode);
        }
        timer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private String buildRequestKey(String method, String pathPattern, int statusCode) {
        return method + "|" + (pathPattern != null ? pathPattern : "") + "|" + statusCode;
    }

    private Timer registerRequestTimer(String method, String pathPattern, int statusCode) {
        String key = buildRequestKey(method, pathPattern, statusCode);
        // 超限后不再为新的 path 组合注册 meter，改计入 (method, status) 维度的溢出桶：
        // 静默丢弃会让超限后的请求完全不可观测，比"分桶粗糙"更糟。
        if (requestTimers.size() >= MAX_CACHED_KEYS) {
            return overflowTimers.computeIfAbsent(method + "|" + statusCode,
                    k -> Timer.builder("dispatcher.request.duration")
                            .tags(TAG_METHOD, method, TAG_PATH, OVERFLOW_PATH, TAG_STATUS, String.valueOf(statusCode))
                            .register(meterRegistry));
        }
        Timer created = Timer
                .builder("dispatcher.request.duration").tags(TAG_METHOD, method, TAG_PATH,
                        pathPattern != null ? pathPattern : "", TAG_STATUS, String.valueOf(statusCode))
                .register(meterRegistry);
        Timer existing = requestTimers.putIfAbsent(key, created);
        return existing != null ? existing : created;
    }

    @Override
    public void recordException(String exceptionType, boolean resolved) {
        String key = exceptionType + "|" + resolved;
        Counter counter = exceptionCounters.computeIfAbsent(key, k -> Counter.builder("dispatcher.exception")
                .tags(TAG_TYPE, exceptionType, TAG_RESOLVED, String.valueOf(resolved)).register(meterRegistry));
        counter.increment();
    }

    @Override
    public void registerPoolGauges(String poolName, ThreadPoolExecutor executor) {
        // 幂等守卫。注：Micrometer 自身对「同名同 tag 同弱引用对象」的 gauge 会去重，
        // 所以重复调用不会产生重复 meter（已实测）；本守卫避免的是重复构建三个 Gauge.builder
        // 并走一遍注册查找的固定开销——该路径在一次启动中每个池只调一次，收益有限，
        // 保留它主要是让"注册"这一动作的语义明确为一次性。
        if (!registeredPoolGauges.add(poolName)) {
            return;
        }
        Gauge.builder("pool." + poolName + ".active.threads", executor, ThreadPoolExecutor::getActiveCount)
                .description("Active threads in " + poolName).register(meterRegistry);

        Gauge.builder("pool." + poolName + ".queue.size", executor, e -> e.getQueue().size())
                .description("Queue size of " + poolName).register(meterRegistry);

        Gauge.builder("pool." + poolName + ".completed.tasks", executor, ThreadPoolExecutor::getCompletedTaskCount)
                .description("Completed tasks of " + poolName).register(meterRegistry);
    }
}
