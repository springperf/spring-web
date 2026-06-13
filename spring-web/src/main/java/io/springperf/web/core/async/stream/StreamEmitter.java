package io.springperf.web.core.async.stream;

import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.context.request.async.DeferredResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public abstract class StreamEmitter<T> {

    protected final DeferredResult<?> deferredResult;

    protected final boolean encodeToString;
    protected AtomicBoolean complete = new AtomicBoolean(false);
    protected List<T> earlySendDataList = new ArrayList<>();
    protected StreamSender streamSender;
    protected Consumer<Throwable> writeCallbackHandler;

    public StreamEmitter() {
        this(true);
    }

    public StreamEmitter(boolean encodeToString) {
        deferredResult = new DeferredResult<>();
        this.encodeToString = encodeToString;
    }

    public StreamEmitter(Long timeout) {
        this(timeout, true);
    }

    public StreamEmitter(Long timeout, boolean encodeToString) {
        if (timeout == null || timeout <= 0) {
            deferredResult = new DeferredResult<>();
        } else {
            deferredResult = new DeferredResult<>(timeout);
        }
        this.encodeToString = encodeToString;
    }

    public void send(T data) throws IOException {
        synchronized (this) {
            if (streamSender != null) {
                streamSender.send(data);
            } else {
                earlySendDataList.add(data);
            }
        }
    }

    protected CharSequence encodeToString(T data) throws IOException {
        throw new UnsupportedOperationException();
    }

    protected byte[] encodeToBytes(T data) throws IOException {
        throw new UnsupportedOperationException();
    }

    protected abstract void extendResponse(ServerHttpResponse response);

    protected int getMaxFlushBytes() {
        return 1024 * 16;
    }

    protected DeferredResult getDeferredResult() {
        return deferredResult;
    }

    protected synchronized void initialize(StreamSender streamSender) throws IOException {
        this.streamSender = streamSender;
        try {
            for (T data : earlySendDataList) {
                streamSender.send(data);
            }
        } finally {
            earlySendDataList.clear();
        }
        if (complete.get() && this.streamSender != null) {
            deferredResult.setResult(null);
            this.streamSender.complete(true, null);
        }
    }

    protected synchronized void initializeWithError(Throwable ex) {
        if (complete.compareAndSet(false, true)) {
            this.earlySendDataList.clear();
            deferredResult.setErrorResult(ex);
        }
    }

    public synchronized void complete() {
        if (complete.compareAndSet(false, true)) {
            if (this.streamSender != null) {
                deferredResult.setResult(null);
                this.streamSender.complete(true, null);
            }
        }
    }

    public synchronized void completeWithError(Throwable ex) {
        if (complete.compareAndSet(false, true)) {
            deferredResult.setErrorResult(ex);
            if (this.streamSender != null) {
                this.streamSender.complete(true, ex);
            }
        }
    }

    public void onTimeout(Runnable callback) {
        this.deferredResult.onTimeout(callback);
    }

    public void onError(Consumer<Throwable> callback) {
        this.deferredResult.onError(callback);
    }

    public void onCompletion(Runnable callback) {
        this.deferredResult.onCompletion(callback);
    }

    public void onWriteCallback(Consumer<Throwable> callback) {
        this.writeCallbackHandler = callback;
    }

    protected Consumer<Throwable> getWriteCallbackHandler() {
        return writeCallbackHandler;
    }
}
