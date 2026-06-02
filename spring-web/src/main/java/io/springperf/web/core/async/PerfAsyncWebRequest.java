package io.springperf.web.core.async;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.WriteRespEventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpAsyncRequestControl;
import org.springframework.web.context.request.async.AsyncWebRequest;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
public class PerfAsyncWebRequest extends PerfNativeWebRequest implements AsyncWebRequest, WriteRespEventListener, ServerHttpAsyncRequestControl {

    public static final RuntimeException DEFAULT_WRITE_ERROR_EXCEPTION = new DefaultWriteErrorException();
    private static final Object RESULT_NONE = new Object();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    protected boolean errorHandlingInProgress;
    private long timeoutMillis = -1;
    private Object concurrentResult = RESULT_NONE;

    private Runnable timeoutHandler;
    private Consumer<Throwable> errorHandler;
    private Runnable completionHandler;
    private Consumer<Throwable> writeCallbackHandler;

    protected PerfAsyncWebRequest(WebServerHttpRequest request, WebServerHttpResponse response) {
        super(request, response);
    }

    public boolean isErrorHandlingInProgress() {
        return errorHandlingInProgress;
    }

    public void startAsyncProcessing() {
        synchronized (this) {
            this.concurrentResult = RESULT_NONE;
            this.errorHandlingInProgress = false;
        }
        this.startAsync();
    }

    public void setConcurrentResultAndDispatch(Object result) {
        synchronized (this) {
            if (this.concurrentResult != RESULT_NONE) {
                return;
            }
            this.concurrentResult = result;
            this.errorHandlingInProgress = (result instanceof Throwable);
        }
        if (this.isAsyncComplete()) {
            return;
        }
        this.dispatch();
    }

    @Override
    public void startAsync() {
        if (!state.compareAndSet(State.NEW, State.ASYNC_STARTED)) {
            throw new IllegalStateException("Async already started");
        }
        response.setWriteRespEventListener(this);
        scheduleTimeoutIfNecessary();
    }

    private void scheduleTimeoutIfNecessary() {
        if (timeoutMillis <= 0 || timeoutHandler == null) {
            return;
        }
        response.setTimeout(() -> {
            if (!state.compareAndSet(State.ASYNC_STARTED, State.COMPLETED)) {
                return;
            }
            if (timeoutHandler != null) {
                timeoutHandler.run();
            }
        }, timeoutMillis);
    }

    @Override
    public void dispatch() {
        if (!state.compareAndSet(State.ASYNC_STARTED, State.DISPATCHED)) {
            return;
        }
        DispatcherHandler dispatcherHandler = request.getWebContext().getDispatcherHandler();
        dispatcherHandler.asyncDispatch(request, response, concurrentResult);
    }

    @Override
    public void setTimeout(Long timeout) {
        this.timeoutMillis = timeout != null ? timeout : -1;
    }

    @Override
    public void completeErrorCallback(Throwable throwable) {
        state.set(State.COMPLETED);
        if (errorHandler != null) {
            errorHandler.accept(throwable);
        }
    }

    @Override
    public void completeSuccessCallback() {
        state.set(State.COMPLETED);
        if (completionHandler != null) {
            completionHandler.run();
        }
    }

    @Override
    public void writeStreamSuccessCallback() {
        if (writeCallbackHandler != null) {
            writeCallbackHandler.accept(null);
        }
    }

    @Override
    public void writeStreamErrorCallback(Throwable throwable) {
        if (writeCallbackHandler != null) {
            if (throwable == null) {
                throwable = DEFAULT_WRITE_ERROR_EXCEPTION;
            }
            writeCallbackHandler.accept(throwable);
        }
    }

    @Override
    public boolean isAsyncStarted() {
        return state.get() == State.ASYNC_STARTED;
    }

    @Override
    public boolean isAsyncComplete() {
        return state.get() == State.COMPLETED;
    }

    @Override
    public void addTimeoutHandler(Runnable handler) {
        this.timeoutHandler = handler;
    }

    @Override
    public void addErrorHandler(Consumer<Throwable> handler) {
        this.errorHandler = handler;
    }

    @Override
    public void addCompletionHandler(Runnable handler) {
        this.completionHandler = handler;
    }

    @Override
    public void start() {
        startAsync();
    }

    @Override
    public void start(long timeout) {
        setTimeout(timeout);
        startAsync();
    }

    @Override
    public boolean isStarted() {
        return state.get() != State.NEW;
    }

    public void addWriteCallbackHandler(Consumer<Throwable> handler) {
        this.writeCallbackHandler = handler;
    }

    @Override
    public boolean isCompleted() {
        return state.get() == State.COMPLETED;
    }

    @Override
    public void complete() {
        completeSuccessCallback();
    }

    protected enum State {NEW, ASYNC_STARTED, DISPATCHED, COMPLETED}

    public static class DefaultWriteErrorException extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}