package io.springperf.web.batch.common;

import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class BatchRequest<R> extends DeferredResult<R> {

    private static final long DEFAULT_TIMEOUT = 30000L;

    // compareAndSet 而非 "if (!completed) completed = true" 的 check-then-act：
    // 批量路径上同一 request 理论上只归属一个批次，但 DROP 失败时的 setError 发生在
    // 生产者线程、超时回调发生在容器线程，guard 本身必须是原子的才谈得上"先到先得"。
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public BatchRequest() {
        super(DEFAULT_TIMEOUT);
    }

    protected BatchRequest(Long timeout) {
        super(timeout);
    }

    @Override
    public final boolean setResult(R result) {
        if (!completed.compareAndSet(false, true)) {
            return false;
        }
        return super.setResult(result);
    }

    @Override
    public boolean setErrorResult(Object result) {
        if (!completed.compareAndSet(false, true)) {
            return false;
        }
        return super.setErrorResult(result);
    }

    public final void setError(Throwable ex) {
        setErrorResult(ex);
    }

    public final boolean isCompleted() {
        return completed.get();
    }
}
