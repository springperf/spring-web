package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;

import java.io.File;
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
     *
     * @return the status, or {@code null} if not set
     */
    HttpStatus getStatus();

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
     * Return the {@link WebContext} associated with this response.
     *
     * @return the web context
     */
    WebContext getWebContext();

    /**
     * Set the event listener for response writing lifecycle callbacks.
     *
     * @param writeRespEventListener the listener to set
     */
    void setWriteRespEventListener(WriteRespEventListener writeRespEventListener);

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
     * Write an {@link InputStream} to the response body as a stream.
     *
     * @param input the input stream to write
     */
    void writeStream(InputStream input);

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
}