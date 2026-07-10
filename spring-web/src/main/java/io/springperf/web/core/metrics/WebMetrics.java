package io.springperf.web.core.metrics;

import io.springperf.web.context.WebComponent;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Core framework metrics collector.
 * <p>
 * SPI for collecting observability data from the request processing pipeline.
 * The default implementation ({@link NoOpWebMetrics}) returns constants for all
 * methods, allowing the JIT to devirtualize, inline, and eliminate the entire
 * metrics path when metrics are not configured.
 * </p>
 *
 * @since 2.7.0
 * @see NoOpWebMetrics
 */
public interface WebMetrics extends WebComponent {

    /**
     * Return a high-resolution timestamp for measuring elapsed time.
     * <p>
     * The default implementation returns {@link System#nanoTime()}.
     * {@link NoOpWebMetrics} overrides this to return {@code 0}, enabling
     * JIT dead-code elimination of the entire timing path.
     *
     * @return current nano timestamp, or {@code 0} for no-op
     */
    default long getNanoTime() {
        return System.nanoTime();
    }

    /**
     * Record a completed request.
     *
     * @param method        HTTP method (GET, POST, etc.)
     * @param pathPattern   matched path pattern, or {@code null} for 404/405
     * @param statusCode    HTTP response status code
     * @param durationNanos request processing duration in nanoseconds
     */
    void recordRequest(String method, String pathPattern, int statusCode, long durationNanos);

    /**
     * Record an exception that occurred during request processing.
     *
     * @param exceptionType fully qualified exception class name
     * @param resolved      whether the exception was handled by an {@code @ExceptionHandler}
     */
    void recordException(String exceptionType, boolean resolved);

    /**
     * Register gauges for a {@link ThreadPoolExecutor}.
     * <p>
     * Implementations should register live-value gauges for:
     * <ul>
     *   <li>active thread count</li>
     *   <li>queue size</li>
     *   <li>completed task count</li>
     * </ul>
     *
     * @param poolName the pool name (bean name or configured name)
     * @param executor the thread pool executor to monitor
     */
    void registerPoolGauges(String poolName, ThreadPoolExecutor executor);
}