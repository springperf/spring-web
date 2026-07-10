package io.springperf.web.core.metrics;

import io.springperf.web.context.BaseWebComponent;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * No-operation {@link WebMetrics} implementation.
 * <p>
 * All methods are empty — no overhead when metrics are not needed.
 * {@link #getNanoTime()} returns {@code 0}, enabling the JIT to eliminate
 * the entire timing path through constant folding and dead-code elimination.
 * </p>
 *
 * @since 2.7.0
 */
public final class NoOpWebMetrics extends BaseWebComponent implements WebMetrics {

    public static final NoOpWebMetrics INSTANCE = new NoOpWebMetrics();

    @Override
    public long getNanoTime() {
        return 0;
    }

    @Override
    public void recordRequest(String method, String pathPattern, int statusCode, long durationNanos) {
    }

    @Override
    public void recordException(String exceptionType, boolean resolved) {
    }

    @Override
    public void registerPoolGauges(String poolName, ThreadPoolExecutor executor) {
    }
}