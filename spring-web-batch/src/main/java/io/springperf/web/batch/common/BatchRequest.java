package io.springperf.web.batch.common;

import org.springframework.web.context.request.async.DeferredResult;

public abstract class BatchRequest<R> extends DeferredResult<R> {

    private static final long DEFAULT_TIMEOUT = 30000L;

    private volatile boolean completed;

    public BatchRequest() {
        super(DEFAULT_TIMEOUT);
    }

    protected BatchRequest(Long timeout) {
        super(timeout);
    }

    @Override
    public final boolean setResult(R result) {
        if (completed) return false;
        completed = true;
        return super.setResult(result);
    }

    @Override
    public boolean setErrorResult(Object result) {
        if (completed) return false;
        completed = true;
        return super.setErrorResult(result);
    }

    public final void setError(Throwable ex) {
        setErrorResult(ex);
    }

    public final boolean isCompleted() {
        return completed;
    }
}