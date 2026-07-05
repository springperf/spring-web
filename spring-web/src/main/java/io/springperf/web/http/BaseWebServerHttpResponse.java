package io.springperf.web.http;

import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

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

@Slf4j
public abstract class BaseWebServerHttpResponse implements WebServerHttpResponse {

    protected final WebContext webContext;
    protected final boolean keepAlive;
    protected final HttpHeaders headers = new WebHttpHeaders();
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
    public void setStatusCode(HttpStatusCode statusCode) {
        if (statusCode != null) {
            this.status = (statusCode instanceof HttpStatus)
                    ? (HttpStatus) statusCode
                    : HttpStatus.valueOf(statusCode.value());
        }
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

    /**
     * 追加 response 写入事件监听器，与已有监听器共存。
     * 若已有监听器，自动用 {@link CompositeWriteRespEventListener} 合并。
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
     * 合并多个 {@link WriteRespEventListener} 的复合监听器。
     * 将回调事件广播到所有子监听器。
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