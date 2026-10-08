package io.springperf.web.core.metrics;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import io.springperf.web.context.BaseWebComponent;

/**
 * Readable {@link WebMetrics} implementation: counts the asynchronous lifecycles currently in flight and exposes the
 * count through {@link #activeAsyncLifecycles()}.
 * <p>
 * Why it exists: {@link NoOpWebMetrics}, the default wiring, deliberately cannot answer "is anything still in flight?".
 * That question cannot be answered afterwards from per-request state either — a leaked async holder is by definition
 * unreachable once its request object is dropped — so a test that wants to assert "every asynchronous lifecycle
 * terminated" needs an implementation that keeps the count. Such tests install this class as a bean; the container
 * prefers a bean over the default, so the count belongs to <b>that context</b> instead of to the JVM.
 * </p>
 * <p>
 * Request, exception and pool metrics stay empty here: this class is about lifecycle accounting. Code that wants the
 * full metric set wants {@code MicrometerWebMetrics} instead.
 * </p>
 */
public class CountingWebMetrics extends BaseWebComponent implements WebMetrics {

    /** 在飞的异步生命周期数：{@link #asyncLifecycleStarted()} +1 / {@link #asyncLifecycleCompleted()} -1。 */
    private final AtomicInteger active = new AtomicInteger();

    @Override
    public void asyncLifecycleStarted() {
        active.incrementAndGet();
    }

    @Override
    public void asyncLifecycleCompleted() {
        active.decrementAndGet();
    }

    /**
     * 当前在飞的异步生命周期数：本 context 的每个异步分发终止后应回到 0。未归零即说明存在未终结的异步生命周期 （入站请求引用仍未归还，是 ByteBuf 泄漏的前置条件）。
     */
    public int activeAsyncLifecycles() {
        return active.get();
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
