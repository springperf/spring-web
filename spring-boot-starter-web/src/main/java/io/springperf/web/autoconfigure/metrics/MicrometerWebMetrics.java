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
 *   <li>{@code dispatcher.request.duration} — Timer, tagged with {@code method}, {@code path}, {@code status}</li>
 *   <li>{@code dispatcher.exception} — Counter, tagged with {@code type}, {@code resolved}</li>
 *   <li>{@code pool.{name}.active.threads} — Gauge, active thread count</li>
 *   <li>{@code pool.{name}.queue.size} — Gauge, queue size</li>
 *   <li>{@code pool.{name}.completed.tasks} — Gauge, completed task count</li>
 * </ul>
 * </p>
 *
 * @since 2.7.0
 */
public class MicrometerWebMetrics extends BaseWebComponent implements WebMetrics {

    private static final String TAG_METHOD = "method";
    private static final String TAG_PATH = "path";
    private static final String TAG_STATUS = "status";
    private static final String TAG_TYPE = "type";
    private static final String TAG_RESOLVED = "resolved";

    private final MeterRegistry meterRegistry;

    /** Cached exception counters per (type, resolved) combination. */
    private final ConcurrentMap<String, Counter> exceptionCounters = new ConcurrentHashMap<>();

    /** Cached timers per (method, path, status) combination. */
    private final ConcurrentMap<String, Timer> requestTimers = new ConcurrentHashMap<>();

    public MicrometerWebMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordRequest(String method, String pathPattern, int statusCode, long durationNanos) {
        String key = method + "|" + (pathPattern != null ? pathPattern : "") + "|" + statusCode;
        Timer timer = requestTimers.get(key);
        if (timer == null) {
            timer = Timer.builder("dispatcher.request.duration")
                    .tags(TAG_METHOD, method,
                          TAG_PATH, pathPattern != null ? pathPattern : "",
                          TAG_STATUS, String.valueOf(statusCode))
                    .register(meterRegistry);
            requestTimers.put(key, timer);
        }
        timer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordException(String exceptionType, boolean resolved) {
        String key = exceptionType + "|" + resolved;
        Counter counter = exceptionCounters.get(key);
        if (counter == null) {
            counter = Counter.builder("dispatcher.exception")
                    .tags(TAG_TYPE, exceptionType,
                          TAG_RESOLVED, String.valueOf(resolved))
                    .register(meterRegistry);
            exceptionCounters.put(key, counter);
        }
        counter.increment();
    }

    @Override
    public void registerPoolGauges(String poolName, ThreadPoolExecutor executor) {
        Gauge.builder("pool." + poolName + ".active.threads", executor, ThreadPoolExecutor::getActiveCount)
                .description("Active threads in " + poolName)
                .register(meterRegistry);

        Gauge.builder("pool." + poolName + ".queue.size", executor, e -> e.getQueue().size())
                .description("Queue size of " + poolName)
                .register(meterRegistry);

        Gauge.builder("pool." + poolName + ".completed.tasks", executor, ThreadPoolExecutor::getCompletedTaskCount)
                .description("Completed tasks of " + poolName)
                .register(meterRegistry);
    }
}