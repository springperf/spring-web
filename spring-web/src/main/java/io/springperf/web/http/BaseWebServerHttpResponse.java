package io.springperf.web.http;

import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class BaseWebServerHttpResponse implements WebServerHttpResponse {

    protected final WebContext webContext;
    protected final boolean keepAlive;
    protected final HttpHeaders headers = new HttpHeaders();
    protected HttpStatus status = HttpStatus.OK;
    protected ByteArrayOutputStream body;
    protected Charset characterEncoding = StandardCharsets.UTF_8;
    protected AtomicBoolean handled = new AtomicBoolean(false);
    protected AtomicBoolean committed = new AtomicBoolean(false);
    protected WriteRespEventListener writeRespEventListener;
    protected ScheduledFuture<?> timeoutFuture;

    public BaseWebServerHttpResponse(WebContext webContext, boolean keepAlive) {
        this.webContext = webContext;
        this.keepAlive = keepAlive;
    }

    public boolean isHandled() {
        return handled.get();
    }

    public boolean isCommitted() {
        return committed.get();
    }

    @Override
    public void setStatusCode(HttpStatus status) {
        if (status != null) this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public OutputStream getBody() {
        if (body == null) body = new ByteArrayOutputStream();
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
        if (body == null) return false;
        boolean haveData = body.size() > 0;
        body.reset();
        return haveData;
    }

    @Override
    public void close() {
        try {
            flush();
        } catch (IOException ignore) {
        }
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
        return setTimeout(this::defaultHandleTimeout, webContext.getProps().getLong(PropertiesConstant.HTTP_TIMEOUT));
    }

    public ScheduledFuture setTimeout(Runnable task, long delay) {
        if (timeoutFuture != null) timeoutFuture.cancel(false);
        if (delay < 0 || task == null) return null;
        timeoutFuture = scheduleOnEventLoop(task, delay, TimeUnit.MILLISECONDS);
        return timeoutFuture;
    }

    abstract void runOnEventLoop(Runnable task);

    abstract ScheduledFuture scheduleOnEventLoop(Runnable task, long delay, TimeUnit unit);

    public WebContext getWebContext() {
        return webContext;
    }

    public void setWriteRespEventListener(WriteRespEventListener writeRespEventListener) {
        this.writeRespEventListener = writeRespEventListener;
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
        if (result) setTimeout(null, -1);
        return result;
    }

    public void sendError(HttpStatus statusCode) {
        sendError(statusCode, statusCode.getReasonPhrase());
    }

    @SneakyThrows
    public void sendError(HttpStatus statusCode, String message) {
        String error = "{\"error\":\"" + message + "\"}";
        writeDataAndFlush(error.getBytes(characterEncoding), MediaType.APPLICATION_JSON, statusCode);
    }

    @SneakyThrows
    protected void writeDataAndFlush(byte[] data, MediaType contentType, HttpStatus statusCode) {
        if (!setHandled()) {
            log.warn("response has been handled. status:{}", statusCode);
            return;
        }
        try {
            setStatusCode(statusCode);
            headers.setContentType(contentType);
            if (data != null) getBody().write(data);
        } finally {
            flush();
        }
    }
}