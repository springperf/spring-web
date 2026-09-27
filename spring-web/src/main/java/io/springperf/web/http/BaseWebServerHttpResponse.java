package io.springperf.web.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

import io.springperf.web.context.WebContext;
import io.springperf.web.server.ErrorPageRenderer;
import io.springperf.web.server.ErrorResponseConfig;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseWebServerHttpResponse implements WebServerHttpResponse {

    protected final WebContext webContext;
    protected boolean keepAlive;
    /** Spring headers 视图，由子类构造方法注入（Netty 子类传可写适配器视图，与 Netty 响应对象共享底层存储） */
    protected final HttpHeaders headers;
    protected HttpStatusCode status = HttpStatus.OK;
    protected ByteArrayOutputStream body;
    protected Charset characterEncoding = StandardCharsets.UTF_8;
    protected AtomicBoolean handled = new AtomicBoolean(false);
    protected AtomicBoolean committed = new AtomicBoolean(false);
    /** 是否处于 chunked 渐进式输出状态（flushChunked/首次 flush(true) 进入，endStream 结束）。 */
    protected final AtomicBoolean streaming = new AtomicBoolean(false);
    /** 流是否已终止（LastHttpContent 已写出；由 endStream 或外部 StreamSender 标记）。 */
    protected final AtomicBoolean streamCompleted = new AtomicBoolean(false);
    /**
     * 提交前回调，仅执行一次。供 servlet 桥在「响应真正提交前」把其 Writer 的编码缓冲刷入响应体 （否则 {@code getWriter().write("x")} 这类未 flush
     * 的写入会随编码缓冲一起丢失）。
     */
    protected volatile Runnable beforeCommit;
    protected WriteRespEventListener writeRespEventListener;
    /**
     * 已装配的响应超时任务。
     * <p>
     * 必须 {@code volatile}：装配方是 EventLoop（{@code setTimeout} / {@code armTimeoutIfAbsent}），取消方是业务线程 （已提交时经
     * {@code setCommitted} → {@code setTimeout(null, -1)}），二者之间没有别的同步点。普通字段在两线程间缺少 happens-before，取消方可能读到 {@code null}
     * 而放过旧 future —— 定时器泄漏，随后对已提交/异步进行中的响应触发 504。
     * </p>
     */
    protected volatile ScheduledFuture<?> timeoutFuture;
    /**
     * 保护 {@link #timeoutFuture} 的复合操作。
     * <p>
     * {@code volatile} 只解决单个读写的可见性，解决不了「读旧 → cancel → 装配新 → 发布」这个 check-then-act
     * 序列：并发装配会让两个线程各自调度一个定时器，先发布的那个被后发布的引用覆盖，从此无法取消 —— 这是本问题的另一半。 该临界区内只有 {@code cancel} 与
     * {@code executor().schedule}（非阻塞），无持有锁做 IO 的风险。
     * </p>
     */
    private final Object timeoutLock = new Object();

    /**
     * 响应超时任务：预先持有（无状态、幂等），避免每次装配都创建 {@code this::defaultHandleTimeout} 方法引用对象（热路径每请求一次装配）。
     */
    private final Runnable defaultHandleTimeoutTask = this::defaultHandleTimeout;

    public BaseWebServerHttpResponse(WebContext webContext, boolean keepAlive, HttpHeaders headers) {
        this.webContext = webContext;
        this.keepAlive = keepAlive;
        this.headers = headers;
    }

    public boolean isHandled() {
        return handled.get();
    }

    public boolean isCommitted() {
        return committed.get();
    }

    @Override
    public void setStatusCode(HttpStatusCode statusCode) {
        if (statusCode != null) {
            this.status = statusCode;
        }
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public OutputStream getBody() {
        if (body == null)
            body = new ByteArrayOutputStream();
        return body;
    }

    public Charset getCharacterEncoding() {
        return characterEncoding;
    }

    public void setCharacterEncoding(Charset characterEncoding) {
        this.characterEncoding = characterEncoding;
    }

    public int getBufferSize() {
        return body == null ? 0 : body.size();
    }

    public boolean resetBuffer() {
        if (body == null)
            return false;
        boolean haveData = body.size() > 0;
        body.reset();
        return haveData;
    }

    @Override
    public void close() {
        try {
            if (streaming.get()) {
                // 渐进式输出：收尾必须写终止块，否则客户端无法判定响应结束（挂到超时）
                endStream();
            } else {
                flush();
            }
        } catch (IOException ignore) {
        }
    }

    @Override
    public boolean isStreaming() {
        return streaming.get();
    }

    @Override
    public boolean markStreamCompleted() {
        // 抢占式语义：返回 true 表示本次调用赢得「终止块写入权」。
        // 一个响应只允许写一个终止块——重复写入会让 HTTP 编码器状态复位后再次收到
        // LastHttpContent，抛 unexpected message type（state: INIT）。
        return streamCompleted.compareAndSet(false, true);
    }

    /** 流是否已终止（供 endStream 幂等判定）。 */
    protected boolean isStreamCompleted() {
        return streamCompleted.get();
    }

    @Override
    public void setBeforeCommit(Runnable callback) {
        this.beforeCommit = callback;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    @Override
    public void flush() throws IOException {
        flush(false);
    }

    protected void defaultHandleTimeout() {
        sendError(HttpStatus.GATEWAY_TIMEOUT, "timeout");
    }

    public ScheduledFuture setTimeout() {
        // server.http.timeout：热路径字段快照直读（首次访问解析，clearCache 后重新解析）
        return setTimeout(defaultHandleTimeoutTask, webContext.getProps().getHttpTimeoutMillis());
    }

    /**
     * 若尚未装配响应超时则装配（幂等）。用于「按需补装配」：处理器被交棒到业务线程池时、 或同步段结束仍未提交（异步/流式等待）时的兜底装配。已装配时不触发 cancel/reschedule。
     */
    @Override
    public void armTimeoutIfAbsent() {
        synchronized (timeoutLock) {
            if (timeoutFuture != null) {
                return;
            }
            setTimeout(defaultHandleTimeoutTask, webContext.getProps().getHttpTimeoutMillis());
        }
    }

    @Override
    public boolean hasTimeoutArmed() {
        // 无锁读：调用方在 EventLoop 判别是否需要兜底装配，不应为此抢装配方的锁
        return timeoutFuture != null;
    }

    public ScheduledFuture setTimeout(Runnable task, long delay) {
        synchronized (timeoutLock) {
            cancelArmedTimeout();
            // delay <= 0：关闭超时（对齐框架「≤0 = 不限制」约定与 Tomcat connectionTimeout=0 的无限语义）。
            // 若把 0 当作「立即触发」，任何配置 server.http.timeout=0 的部署都会全量 504。
            if (delay <= 0 || task == null) {
                return null;
            }
            timeoutFuture = scheduleOnEventLoop(task, delay, TimeUnit.MILLISECONDS);
            return timeoutFuture;
        }
    }

    /** 取消并摘除已装配的超时任务：调用方必须持有 {@link #timeoutLock}。 */
    private void cancelArmedTimeout() {
        ScheduledFuture<?> current = timeoutFuture;
        if (current != null) {
            timeoutFuture = null;
            current.cancel(false);
        }
    }

    abstract void runOnEventLoop(Runnable task);

    abstract ScheduledFuture scheduleOnEventLoop(Runnable task, long delay, TimeUnit unit);

    public WebContext getWebContext() {
        return webContext;
    }

    public void setWriteRespEventListener(WriteRespEventListener writeRespEventListener) {
        this.writeRespEventListener = writeRespEventListener;
    }

    /**
     * 追加 response 写入事件监听器，与已有监听器共存。 若已有监听器，自动用 {@link CompositeWriteRespEventListener} 合并。
     */
    public void addWriteRespEventListener(WriteRespEventListener listener) {
        if (this.writeRespEventListener == null) {
            this.writeRespEventListener = listener;
        } else if (this.writeRespEventListener instanceof CompositeWriteRespEventListener) {
            ((CompositeWriteRespEventListener) this.writeRespEventListener).add(listener);
        } else {
            this.writeRespEventListener = new CompositeWriteRespEventListener(this.writeRespEventListener, listener);
        }
    }

    /**
     * 合并多个 {@link WriteRespEventListener} 的复合监听器。 将回调事件广播到所有子监听器。
     */
    private static class CompositeWriteRespEventListener implements WriteRespEventListener {
        private final List<WriteRespEventListener> listeners = new ArrayList<>(2);

        CompositeWriteRespEventListener(WriteRespEventListener first, WriteRespEventListener second) {
            listeners.add(first);
            listeners.add(second);
        }

        void add(WriteRespEventListener listener) {
            listeners.add(listener);
        }

        @Override
        public void completeSuccessCallback() {
            for (WriteRespEventListener l : listeners) {
                l.completeSuccessCallback();
            }
        }

        @Override
        public void completeErrorCallback(Throwable throwable) {
            for (WriteRespEventListener l : listeners) {
                l.completeErrorCallback(throwable);
            }
        }

        @Override
        public void writeStreamSuccessCallback() {
            for (WriteRespEventListener l : listeners) {
                l.writeStreamSuccessCallback();
            }
        }

        @Override
        public void writeStreamErrorCallback(Throwable throwable) {
            for (WriteRespEventListener l : listeners) {
                l.writeStreamErrorCallback(throwable);
            }
        }
    }

    public boolean setHandled() {
        return handled.compareAndSet(false, true);
    }

    public void resetHandled() {
        handled.set(false);
    }

    protected boolean setCommitted() {
        setHandled();
        boolean result = committed.compareAndSet(false, true);
        if (result) {
            // 提交即取消响应超时
            setTimeout(null, -1);
            runBeforeCommitOnce();
        }
        return result;
    }

    /**
     * 执行「提交前回调」（幂等，仅一次）。
     * <p>
     * 凡是在提交时读取响应体缓冲的路径，都必须在**捕获缓冲引用之前**调用本方法： 回调（servlet 桥把 Writer 编码缓冲刷入响应体）会把内容写入<b>当前</b>缓冲，
     * 若调用方已经捕获了旧引用，新内容就会落在被忽略的新缓冲里而丢失。
     * </p>
     */
    protected void runBeforeCommitOnce() {
        Runnable cb = beforeCommit;
        if (cb != null) {
            beforeCommit = null;
            try {
                cb.run();
            } catch (Throwable t) {
                log.warn("beforeCommit callback failed: {}", t.getMessage(), t);
            }
        }
    }

    public void sendError(HttpStatus statusCode) {
        sendError(statusCode, statusCode.getReasonPhrase());
    }

    @SneakyThrows
    public void sendError(HttpStatus statusCode, String message) {
        sendError((HttpStatusCode) statusCode, message);
    }

    @SneakyThrows
    public void sendError(HttpStatusCode statusCode, String message) {
        sendError(statusCode, message, null, false, false);
    }

    /**
     * 完整错误响应入口：按 {@link ErrorResponseConfig}（从 {@link #webContext} 读取，缺失则用默认） 渲染 whitelabel HTML 或 JSON，并按
     * {@code on-param} 模式决定是否暴露异常栈 / message。
     */
    @SneakyThrows
    public void sendError(HttpStatusCode statusCode, String message, Throwable cause, boolean includeStacktraceOnParam,
            boolean includeMessageOnParam) {
        sendError(statusCode, message, cause, includeStacktraceOnParam, includeMessageOnParam, false);
    }

    @Override
    @SneakyThrows
    public void sendError(HttpStatusCode statusCode, String message, Throwable cause, boolean includeStacktraceOnParam,
            boolean includeMessageOnParam, boolean includeErrorsOnParam) {
        ErrorResponseConfig cfg = resolveErrorConfig();
        ErrorPageRenderer.ErrorResponseBody body = ErrorPageRenderer.build(statusCode, message, cause, cfg,
                includeStacktraceOnParam, includeMessageOnParam, includeErrorsOnParam, acceptHeader);
        writeDataAndFlush(body.getBody().getBytes(characterEncoding), MediaType.parseMediaType(body.getContentType()),
                statusCode);
    }

    /** 当前请求的 Accept 头（由管线在构造响应时注入；用于 whitelabel/JSON 错误体内容协商）。 */
    private String acceptHeader;

    /**
     * 注入请求 Accept 头：对齐 Boot {@code BasicErrorController} 的 produces 协商—— 浏览器（text/html 或 *&#47;*）得到 whitelabel HTML，显式
     * JSON 客户端得到 JSON。
     */
    public void setRequestAcceptHeader(String acceptHeader) {
        this.acceptHeader = acceptHeader;
    }

    /** 读取错误响应配置：未注册（如非 Netty 嵌入场景）时回退默认（whitelabel 开启、三项均 never）。 */
    private ErrorResponseConfig resolveErrorConfig() {
        ErrorResponseConfig cfg = webContext.getWebComponent(ErrorResponseConfig.class);
        return cfg != null ? cfg : ErrorResponseConfig.DEFAULT;
    }

    @SneakyThrows
    protected void writeDataAndFlush(byte[] data, MediaType contentType, HttpStatusCode statusCode) {
        if (!setHandled()) {
            log.warn("response has been handled. status:{}", statusCode);
            return;
        }
        try {
            // 先让 servlet Writer 的编码缓冲落入响应体，随后被 resetBuffer 一并清掉——
            // 对齐 Tomcat：错误响应不得夹带业务先前写入的内容。
            runBeforeCommitOnce();
            // 异常路径：清空已缓冲的部分 body（如 JSON 序列化中途失败写入的字节），
            // 避免错误响应 JSON 追加在部分内容之后形成畸形响应体（对齐 Spring 语义）。
            resetBuffer();
            setStatusCode(statusCode);
            headers.setContentType(contentType);
            if (data != null)
                getBody().write(data);
        } finally {
            flush();
        }
    }
}
