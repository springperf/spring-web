package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.ScheduledFuture;

/**
 * Server-side HTTP response abstraction for the perf web framework.
 * <p>
 * Extends Spring's {@link ServerHttpResponse} with additional methods for
 * character encoding, buffer management, timeout scheduling, error sending,
 * and streaming file responses.
 */
public interface WebServerHttpResponse extends ServerHttpResponse {

    /**
     * Check whether the response has been handled by the application.
     *
     * <p>A handled response has had its status, headers, and body written.
     * The framework checks this flag before flushing to avoid double-writes.</p>
     *
     * @return {@code true} if the response has been handled
     */
    boolean isHandled();

    /**
     * Check whether the response has been committed (flushed to the client).
     *
     * @return {@code true} if the response is committed
     */
    boolean isCommitted();

    /**
     * Return the HTTP status set on this response.
     * <p>支持非标准状态码（如 499/599）：{@link HttpStatusCode} 可以是
     * {@code DefaultHttpStatusCode}，不再强制收敛到标准 {@link HttpStatus}。</p>
     *
     * @return the status, or {@code null} if not set
     */
    HttpStatusCode getStatus();

    /**
     * Return the character encoding of the response body.
     *
     * @return the character encoding, or {@code null} if not set
     */
    Charset getCharacterEncoding();

    /**
     * Set the character encoding for writing the response body.
     *
     * @param characterEncoding the encoding to use
     */
    void setCharacterEncoding(Charset characterEncoding);

    /**
     * Return the response buffer size in bytes.
     *
     * @return the buffer size
     */
    int getBufferSize();

    /**
     * Reset the response buffer, clearing any buffered content.
     *
     * @return {@code true} if the buffer was successfully reset
     */
    boolean resetBuffer();

    /**
     * Schedule a task to run after the specified delay.
     *
     * <p>Used for response timeout handling. The returned
     * {@link ScheduledFuture} can be used to cancel the timeout.</p>
     *
     * @param task  the task to run
     * @param delay the delay in milliseconds
     * @return a {@code ScheduledFuture} for cancellation
     */
    ScheduledFuture setTimeout(Runnable task, long delay);

    /**
     * 若尚未装配响应超时则装配（幂等）；已装配时为 no-op。
     *
     * <p>与 {@link #setTimeout(Runnable, long)} 的区别：后者会「取消旧任务 + 重新调度」，用于
     * 重置计时（如异步开始时按 {@code spring.mvc.async.request-timeout} 重装）；本方法用于
     * <b>按需补装配</b>——例如处理器被交棒到业务线程池时补装配，或同步段结束仍未提交时兜底，
     * 避免重复装配造成 cancel/reschedule 抖动。</p>
     *
     * <p>默认实现为空操作：未覆写的实现类保持各自既有装配行为。</p>
     */
    default void armTimeoutIfAbsent() {
    }

    /**
     * 是否已装配响应超时。
     *
     * <p>用于判断「是否需要补装配」：{@code false} 表示当前没有任何定时任务在跑。
     * 默认实现返回 {@code false}（未覆写的实现类视为未装配）。</p>
     */
    default boolean hasTimeoutArmed() {
        return false;
    }

    /**
     * Return the {@link WebContext} associated with this response.
     *
     * @return the web context
     */
    WebContext getWebContext();

    /**
     * Set the event listener for response writing lifecycle callbacks.
     * 替换所有已注册的监听器。
     *
     * @param writeRespEventListener the listener to set
     */
    void setWriteRespEventListener(WriteRespEventListener writeRespEventListener);

    /**
     * 添加一个 response 写入事件监听器，追加到已有监听器列表。
     * 与 {@link #setWriteRespEventListener} 不同，此方法不会覆盖已有监听器。
     */
    default void addWriteRespEventListener(WriteRespEventListener writeRespEventListener) {
        // 默认实现：直接替换（兼容尚未覆写此方法的实现）
        setWriteRespEventListener(writeRespEventListener);
    }

    /**
     * Mark the response as handled.
     *
     * @return {@code true} if the response was not previously handled
     */
    boolean setHandled();

    /**
     * Send an error response with the given status code.
     *
     * @param statusCode the HTTP status code
     */
    void sendError(HttpStatus statusCode);

    /**
     * Send an error response with the given status code and message.
     *
     * @param statusCode the HTTP status code
     * @param message    the error message
     */
    void sendError(HttpStatus statusCode, String message);

    /**
     * Send an error response with the given status code and message.
     * <p>支持非标准状态码（如 499/599）：调用方可传入 {@link HttpStatusCode#valueOf(int)}
     * 得到的 {@code DefaultHttpStatusCode}，由底层按原始码值写入响应。</p>
     *
     * @param statusCode the HTTP status code
     * @param message    the error message
     */
    void sendError(HttpStatusCode statusCode, String message);

    /**
     * Send an error response carrying the original {@link Throwable}，使底层可按
     * {@code server.error.include-stacktrace}/{@code include-binding-errors} 暴露栈与校验错误。
     * 默认实现退化为 {@link #sendError(HttpStatusCode, String)}。
     *
     * @param statusCode the HTTP status code
     * @param message    the error message
     * @param cause      the original exception (may be {@code null})
     */
    default void sendError(HttpStatusCode statusCode, String message, Throwable cause) {
        sendError(statusCode, message);
    }

    /**
     * 完整错误响应入口：携带异常并按 {@code on-param} 模式（请求参数 {@code trace}/{@code message}
     * 命中时）决定是否暴露栈/消息。默认实现退化为 {@link #sendError(HttpStatusCode, String, Throwable)}。
     *
     * @param statusCode              the HTTP status code
     * @param message                 the error message
     * @param cause                   the original exception (may be {@code null})
     * @param includeStacktraceOnParam 是否命中 trace 参数（on-param 模式）
     * @param includeMessageOnParam   是否命中 message 参数（on-param 模式）
     */
    default void sendError(HttpStatusCode statusCode, String message, Throwable cause,
                          boolean includeStacktraceOnParam, boolean includeMessageOnParam) {
        sendError(statusCode, message, cause, includeStacktraceOnParam, includeMessageOnParam, false);
    }

    /**
     * 完整错误响应入口（含 {@code errors} 参数门控）：{@code on-param} 模式下请求参数
     * {@code trace}/{@code message}/{@code errors} 分别控制异常栈 / message / 绑定错误的暴露。
     *
     * @param includeErrorsOnParam 是否命中 errors 参数（on-param 模式，影响 include-binding-errors）
     */
    default void sendError(HttpStatusCode statusCode, String message, Throwable cause,
                          boolean includeStacktraceOnParam, boolean includeMessageOnParam,
                          boolean includeErrorsOnParam) {
        sendError(statusCode, message, cause);
    }

    /**
     * Write an {@link InputStream} to the response body as a stream.
     *
     * @param input the input stream to write
     */
    void writeStream(InputStream input);

    /**
     * Write exactly {@code contentLength} bytes of {@code input} using a
     * {@code Content-Length} framed response instead of chunked transfer encoding.
     *
     * <p>用于长度已知且必须向客户端声明长度的场景（静态资源、206 分片响应）：
     * chunked 不携带总长度，客户端无法显示进度、也无法预知大小；且 HTTP 消息同时带
     * {@code Content-Length} 与 {@code Transfer-Encoding} 违反 RFC 7230 §3.3.1。</p>
     *
     * <p>默认实现退回 {@link #writeStream(InputStream)}（忽略长度），实现类应覆盖。</p>
     *
     * @param input         the input stream to write
     * @param contentLength number of bytes to send (must be non-negative)
     */
    default void writeStream(InputStream input, long contentLength) {
        writeStream(input);
    }

    /**
     * 以 chunked 帧提交 / 续写响应体（渐进式输出）。语义对齐 Tomcat 的
     * {@code ServletResponse.flushBuffer()} 与 {@code getWriter().flush()}：
     * 首次调用提交响应头（{@code Transfer-Encoding: chunked}）并发送已缓冲内容，
     * 之后每次调用把新增内容作为独立内容帧发出。
     *
     * <p>调用方必须在请求收尾时调用 {@link #endStream()} 写终止块，否则客户端无法判定
     * 响应结束。默认实现退回一次性 {@link #flush(boolean)}。</p>
     */
    default void flushChunked() throws IOException {
        flush(true);
    }

    /**
     * 终止 chunked 流（幂等）：写出残留内容帧与 {@code LastHttpContent}。
     * 非流式响应（一次性 Content-Length 或从未提交）为空操作。
     */
    default void endStream() {
    }

    /** 是否处于 chunked 渐进式输出状态。 */
    default boolean isStreaming() {
        return false;
    }

    /**
     * 由外部流写入方（{@code StreamSender} 直写 channel 的场景）标记流已终止，
     * 使请求收尾的 {@link #endStream()} 成为空操作（避免写出第二个终止块）。
     */
    default boolean markStreamCompleted() {
        return false;
    }

    /**
     * 注册「提交前回调」，仅执行一次（响应首次真正提交前）。
     *
     * @param callback 回调（例如把 servlet Writer 的编码缓冲刷入响应体）
     */
    default void setBeforeCommit(Runnable callback) {
    }

    /**
     * Write a byte array to the response body with {@code Content-Length} header
     * in a single write-and-flush operation.
     * <p>Unlike {@link #writeStream}, this method avoids chunked transfer encoding
     * and sends headers + body in one TCP segment, which is significantly more
     * efficient for small known-size payloads.</p>
     *
     * @param data the byte array to write
     */
    void writeBytes(byte[] data);

    /**
     * Write a {@link File} to the response body.
     *
     * @param file the file to write
     */
    void writeFile(File file);

    /**
     * Flush the response body.
     *
     * @param chunked whether the response is chunked
     */
    void flush(boolean chunked) throws IOException;

    /**
     * 兜底释放未被提交（未被 flush/sendError 消费）的响应体缓冲，避免池化 ByteBuf 泄漏。
     * <p>默认实现为空操作；{@code NettyServerHttpResponse} 在请求 {@code release()} 时级联调用，
     * 使响应未提交 buf 的生命周期绑定到请求生命周期（同步与异步路径统一，无需异步特例守卫）。</p>
     */
    default void release() {
    }
}