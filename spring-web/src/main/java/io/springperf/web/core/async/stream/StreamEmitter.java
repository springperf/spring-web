package io.springperf.web.core.async.stream;

import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.context.request.async.DeferredResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public abstract class StreamEmitter<T> {

    protected final DeferredResult<?> deferredResult;

    protected AtomicBoolean complete = new AtomicBoolean(false);
    /**
     * earlyEncode=true 时存储 byte[]（已编码快照）；
     * earlyEncode=false 时存储原始 T 对象。
     */
    protected List<Object> earlySendDataList = new ArrayList<>();
    protected volatile StreamSender streamSender;
    protected Consumer<Throwable> writeCallbackHandler;

    private final boolean earlyEncode;

    public StreamEmitter() {
        this(false);
    }

    public StreamEmitter(boolean earlyEncode) {
        this.earlyEncode = earlyEncode;
        deferredResult = new DeferredResult<>();
    }

    public StreamEmitter(Long timeout) {
        this(timeout, false);
    }

    public StreamEmitter(Long timeout, boolean earlyEncode) {
        this.earlyEncode = earlyEncode;
        if (timeout == null || timeout <= 0) {
            deferredResult = new DeferredResult<>();
        } else {
            deferredResult = new DeferredResult<>(timeout);
        }
    }

    public boolean isEarlyEncode() {
        return earlyEncode;
    }

    /**
     * 发送数据。
     * <p>
     * earlyEncode=true：在 App 线程调用 {@link #encode(Object, OutputStream)} 编码为 byte[] 快照，
     * 再投递到发送器。确保发送时数据已冻结，线程安全。
     * <p>
     * earlyEncode=false：原始数据直接投递，由发送器（EventLoop 线程）延迟编码。
     */
    public void send(T data) throws IOException {
        Object payload = data;
        if (earlyEncode) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
            encode(data, baos);
            payload = baos.toByteArray();
        }
        StreamSender s = this.streamSender;
        if (s != null) {
            s.send(payload);
            return;
        }
        synchronized (this) {
            if (this.streamSender != null) {
                this.streamSender.send(payload);
            } else {
                earlySendDataList.add(payload);
            }
        }
    }

    public abstract void encode(Object data, OutputStream out) throws IOException;

    /**
     * encode 失败回调。子类可覆写此方法决定如何处理编码失败的数据。
     * <p>
     * 默认实现为空（仅打日志），编码失败的数据被静默丢弃，不会中断流。
     * 子类可改为发送 SSE 错误帧、标记流为错误状态等。
     *
     * @param data 编码失败的数据（原始值，未编码）
     * @param ex   编码异常
     */
    protected void onEncodeError(Object data, Exception ex) {
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
            // 批量交付：逐条 send() 在 EventLoop 上会每条触发一次同步 drain 并单独
            // flush，破坏 drain() 的 batchBuf 批量编码；sendAll 入队后仅调度一次 drain。
            streamSender.sendAll(earlySendDataList);
        } finally {
            earlySendDataList.clear();
        }
        if (complete.get() && this.streamSender != null) {
            deferredResult.setResult(null);
            this.streamSender.complete(false, null);
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
            StreamSender s = this.streamSender;
            if (s != null) {
                deferredResult.setResult(null);
                s.complete(false, null);
            }
        }
    }

    public synchronized void completeWithError(Throwable ex) {
        if (complete.compareAndSet(false, true)) {
            deferredResult.setErrorResult(ex);
            StreamSender s = this.streamSender;
            if (s != null) {
                s.complete(false, ex);
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
