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
    private Runnable asyncReadyCallback;

    protected PerfAsyncWebRequest(WebServerHttpRequest request, WebServerHttpResponse response) {
        super(request, response);
    }

    /**
     * 连接断开（客户端消失）时的取消钩子：供上游订阅（reactive）/ 长任务在断连时主动退场。
     *
     * <p>原子取出并清空，保证并发断连信号下钩子至多执行一次。</p>
     */
    private final AtomicReference<Runnable> connectionCloseHandler = new AtomicReference<>();

    /**
     * 注册「客户端断连」钩子。
     *
     * <p>写入回调只在【下一次投递失败】时触发；若源在断连后不再投递（长轮询挂住、DB 游标等待），
     * 就永远不会取消。断连本身必须能作为取消信号。</p>
     */
    public void addConnectionCloseHandler(Runnable handler) {
        this.connectionCloseHandler.set(handler);
    }

    /**
     * 连接在响应写出前关闭（客户端中断）时由 {@code NettyHttpHandler} 调用：让异步持有者退场。
     *
     * <p>覆盖「空闲流 / 未完成异步被直接断连」——此时既不写 {@code LastHttpContent}，也没有 chunk
     * 写失败，若不在此退场，入站 buf 要等请求对象被 GC 才释放。只做生命周期清理，不触发业务
     * errorHandler（客户端中断不是业务错误，避免日志/指标噪声）。</p>
     */
    public void releaseOnConnectionClose() {
        state.compareAndSet(State.ASYNC_STARTED, State.COMPLETED);
        Runnable handler = this.connectionCloseHandler.getAndSet(null);
        if (handler != null) {
            try {
                handler.run();
            } catch (Throwable ignored) {
                // 断连清理路径：钩子自身异常不得影响后续流程
            }
        }
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
        response.addWriteRespEventListener(this);
    }

    public void scheduleTimeoutIfNeeded() {
        if (timeoutMillis <= 0 || timeoutHandler == null) {
            return;
        }
        if (state.get() != State.ASYNC_STARTED) {
            return;
        }
        response.setTimeout(() -> {
            if (state.get() != State.ASYNC_STARTED) {
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

    public boolean isAsyncDispatched() {
        return state.get() == State.DISPATCHED;
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

    public void setAsyncReadyCallback(Runnable callback) {
        this.asyncReadyCallback = callback;
    }

    public void executeAsyncReadyCallback() {
        if (asyncReadyCallback != null) {
            asyncReadyCallback.run();
            asyncReadyCallback = null;
        }
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