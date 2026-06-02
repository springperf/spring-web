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

    boolean isHandled();

    boolean isCommitted();

    HttpStatus getStatus();

    Charset getCharacterEncoding();

    void setCharacterEncoding(Charset characterEncoding);

    int getBufferSize();

    boolean resetBuffer();

    ScheduledFuture setTimeout(Runnable task, long delay);

    WebContext getWebContext();

    void setWriteRespEventListener(WriteRespEventListener writeRespEventListener);

    boolean setHandled();

    void sendError(HttpStatus statusCode);

    void sendError(HttpStatus statusCode, String message);

    void writeStream(InputStream input);

    void writeFile(File file);
}