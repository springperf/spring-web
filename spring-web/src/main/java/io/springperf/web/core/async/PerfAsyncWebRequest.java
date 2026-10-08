package io.springperf.web.core.async;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.http.server.ServerHttpAsyncRequestControl;
import org.springframework.web.context.request.async.AsyncWebRequest;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.metrics.NoOpWebMetrics;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.WriteRespEventListener;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PerfAsyncWebRequest extends PerfNativeWebRequest
        implements AsyncWebRequest, WriteRespEventListener, ServerHttpAsyncRequestControl {

    public static final RuntimeException DEFAULT_WRITE_ERROR_EXCEPTION = new DefaultWriteErrorException();
    private static final Object RESULT_NONE = new Object();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    /** 异步持有者是否仍持有入站请求引用（startAsync 时 +1，写终结时 -1；见 releaseRequestOnce）。 */
    private final AtomicBoolean requestRefHeld = new AtomicBoolean(false);

    /**
     * 本请求所属 context 的计量组件，**懒解析**（首次用到时才查容器，解析结果缓存在此）。
     * <p>
     * 懒解析不是可选项：既有单测锁定了「无操作路径不触碰 {@link WebContext}」这一契约 —— {@code dispatch()} 在未启动 / 已完成时直接返回， 连 dispatcher handler
     * 都不查。构造期解析会破坏它（实测 3 个用例 {@code NeverWantedButInvoked}）。异步生命周期的两个端点（{@code startAsync} /
     * {@code releaseRequestOnce}）都只在异步路径上，故解析也只发生在那条路径上 —— 同步热路径依旧零触碰。
     * </p>
     * <p>
     * 在飞计数落在这里，而不再是全局静态字段：全局字段把「归零」变成整个 JVM 的不变量，任何别的 context 的残留都会污染断言（实测：整仓 {@code clean test} 下 28 条级联误报 /
     * 422.5s）。默认装配是 {@link NoOpWebMetrics}，两个钩子都是空实现 → 不装计量时零开销。volatile：写入方可能是业务线程 （startAsync），读取方可能是 EventLoop（写回调里的
     * releaseRequestOnce）。
     * </p>
     */
    private volatile WebMetrics metrics;

    private WebMetrics metrics() {
        WebMetrics resolved = this.metrics;
        if (resolved == null) {
            resolved = resolveMetrics(request);
            this.metrics = resolved;
        }
        return resolved;
    }

    protected boolean errorHandlingInProgress;
    private long timeoutMillis = -1;
    /**
     * 结果槽：非 RESULT_NONE 即已被并发 dispatch 抢占写入。
     * <p>
     * <b>发布边是 {@code state} 的 CAS，而不是 {@code synchronized(this)}</b>：写入确实在锁内，但读取发生在 {@link #dispatch()} 的
     * {@code state.compareAndSet(ASYNC_STARTED, DISPATCHED)} 之后（volatile 读 → 写入对读取线程可见）。同一依据也记在
     * {@code spotbugs-exclude.xml}。 改动此处时不要削弱那次 CAS：它同时承担互斥与可见性。
     * </p>
     */
    private Object concurrentResult = RESULT_NONE;

    // 以下回调字段统一 volatile：写入方是业务线程（注册钩子 / 启动异步），读取方是 EventLoop
    // （写回调、超时任务、断连回调）。二者之间没有同步点，普通字段会让 EventLoop 侧读到 null
    // 而静默跳过回调——表现为超时不生效、错误不被记录、异步完成时到不了 completionHandler。
    private volatile Runnable timeoutHandler;
    private volatile Consumer<Throwable> errorHandler;
    private volatile Runnable completionHandler;
    private volatile Consumer<Throwable> writeCallbackHandler;
    private volatile Runnable asyncReadyCallback;

    protected PerfAsyncWebRequest(WebServerHttpRequest request, WebServerHttpResponse response) {
        super(request, response);
    }

    /**
     * 取本 context 的计量组件；取不到就退化为 {@link NoOpWebMetrics#INSTANCE}（而不是 null）——单元测试的请求替身 没有
     * WebContext，而「计量缺失」不该让异步路径出异常。用只读的 {@code getWebComponent} 而非
     * {@code getWebComponentWithDefault}：后者会**注册**默认实现，那是启动期该做的事，不是每请求该做的。
     */
    private static WebMetrics resolveMetrics(WebServerHttpRequest request) {
        WebContext webContext = request.getWebContext();
        if (webContext == null) {
            return NoOpWebMetrics.INSTANCE;
        }
        WebMetrics component = webContext.getWebComponent(WebMetrics.class);
        return component != null ? component : NoOpWebMetrics.INSTANCE;
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
        // 异步持有者诞生：CAS 成功保证只执行一次。异步阶段若读请求体（大 body 为 content 的
        // duplicate 共享视图）依赖入站 buf 存活，故在此 acquire，由 releaseRequestOnce() 归还。
        request.acquire();
        requestRefHeld.set(true);
        metrics().asyncLifecycleStarted();
        response.addWriteRespEventListener(this);
    }

    /**
     * 异步持有者退场（幂等，仅一次）：入站请求引用 -1。
     * <p>
     * 只在响应【写终结】回调里调用（{@code completeSuccessCallback}/{@code completeErrorCallback}）： 写完成时响应已提交，请求释放触发的
     * {@code resp.release()} 为空操作；写失败时响应永无提交机会， 恰好兜底释放其未提交 buf。超时/断连无需另设释放点——超时响应是一次写入、断连会让写入 future 失败，两者都会走到这里。
     * </p>
     * <p>
     * <b>切勿</b>在 {@code writeStreamSuccessCallback} 等【逐 chunk】回调里调用——那是每帧触发。
     * </p>
     */
    private void releaseRequestOnce() {
        if (requestRefHeld.compareAndSet(true, false)) {
            request.release();
            metrics().asyncLifecycleCompleted();
        }
    }

    /**
     * 连接在响应写出前关闭（客户端中断）时由 NettyHttpHandler 调用：异步持有者退场。
     * <p>
     * 覆盖「空闲流 / 未完成异步被直接断连」——此时既不写 {@code LastHttpContent}（无 completeSuccessCallback），也没有 chunk 写失败（无
     * completeErrorCallback / writeStreamErrorCallback）；若不在此退场，入站 buf 要等请求对象被 GC 才释放。
     * </p>
     * <p>
     * 只做生命周期清理，<b>不</b>触发业务 errorHandler（客户端中断不是业务错误，避免日志/指标噪声）。 释放时其他持有者（业务池任务那一次 acquire）仍持有各自引用，故正在读 body 的线程不受影响。
     * </p>
     */
    /**
     * 连接断开（客户端消失）时的取消钩子：供上游订阅（reactive）/长任务在断连时主动退场。
     * <p>
     * 用 {@link AtomicReference} 而非 volatile 字段：注册方是业务线程（reactive 订阅建立时），执行方是 EventLoop（断连回调）。 除了可见性，这里还需要「取出并清空」是原子的 ——
     * 原先的 {@code 读 → 判非空 → 置 null} 三步在并发断连信号下 会让两个线程同时读到同一个 handler 并各跑一次。
     * </p>
     */
    private final AtomicReference<Runnable> connectionCloseHandler = new AtomicReference<>();

    /**
     * 注册「客户端断连」钩子。
     * <p>
     * 为什么需要独立钩子：写入回调（{@code addWriteCallbackHandler}）只在【下一次投递失败】时 触发；若源在断连后不再投递（长轮询挂住、DB 游标等待），就永远不会取消 →
     * 每个断连客户端都会留下一个仍在运行的源。断连本身必须能作为取消信号。
     * </p>
     */
    public void addConnectionCloseHandler(Runnable handler) {
        this.connectionCloseHandler.set(handler);
    }

    public void releaseOnConnectionClose() {
        state.compareAndSet(State.ASYNC_STARTED, State.COMPLETED);
        // 原子取出并清空：无论多少个断连信号并发到达，钩子至多执行一次
        Runnable handler = this.connectionCloseHandler.getAndSet(null);
        if (handler != null) {
            try {
                handler.run();
            } catch (Throwable ignored) {
                // 断连清理路径：钩子自身异常不得影响后续引用归零
            }
        }
        releaseRequestOnce();
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
        releaseRequestOnce();
        if (errorHandler != null) {
            errorHandler.accept(throwable);
        }
    }

    @Override
    public void completeSuccessCallback() {
        state.set(State.COMPLETED);
        releaseRequestOnce();
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
        // 流式路径的【异常终结点】：AbstractNettyStreamSender.onAllDataFailed() 只关连接、
        // 不挂 isComplete 监听器（正常结束那条走 onAllDataWritten → addRespEventListener(f,true)，
        // 已汇入 completeSuccessCallback），故这里必须让异步持有者退场，否则入站 buf 泄漏。
        // 单次守卫 + 流失败即终止 ⇒ 不会重复释放。
        releaseRequestOnce();
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

    protected enum State {
        NEW, ASYNC_STARTED, DISPATCHED, COMPLETED
    }

    public static class DefaultWriteErrorException extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
