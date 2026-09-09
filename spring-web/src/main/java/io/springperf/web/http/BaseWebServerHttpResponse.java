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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class BaseWebServerHttpResponse implements WebServerHttpResponse {

    protected final WebContext webContext;
    protected final boolean keepAlive;
    /** Spring headers 视图，由子类构造方法注入（Netty 子类传可写适配器视图，与 Netty 响应对象共享底层存储） */
    protected final HttpHeaders headers;
    protected HttpStatus status = HttpStatus.OK;
    protected ByteArrayOutputStream body;
    protected Charset characterEncoding = StandardCharsets.UTF_8;
    protected AtomicBoolean handled = new AtomicBoolean(false);
    protected AtomicBoolean committed = new AtomicBoolean(false);
    protected WriteRespEventListener writeRespEventListener;
    protected ScheduledFuture<?> timeoutFuture;

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
        String error = "{\"error\":\"" + escapeJson(message) + "\"}";
        writeDataAndFlush(error.getBytes(characterEncoding), MediaType.APPLICATION_JSON, statusCode);
    }

    /**
     * 对写入 JSON 字符串字面量的 message 做转义，防止 {@code "}、{@code \} 及控制字符破坏响应体 JSON。
     */
    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    @SneakyThrows
    protected void writeDataAndFlush(byte[] data, MediaType contentType, HttpStatus statusCode) {
        if (!setHandled()) {
            log.warn("response has been handled. status:{}", statusCode);
            return;
        }
        try {
            // 异常路径：清空已缓冲的部分 body（如 JSON 序列化中途失败写入的字节），
            // 避免错误响应 JSON 追加在部分内容之后形成畸形响应体（对齐 Spring 语义）。
            resetBuffer();
            setStatusCode(statusCode);
            headers.setContentType(contentType);
            if (data != null) getBody().write(data);
        } finally {
            flush();
        }
    }
}