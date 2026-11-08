package io.springperf.web.support.mvc.retval;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.locks.LockSupport;

import org.springframework.http.server.ServerHttpResponse;

import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.core.async.stream.StreamEmitter;
import io.springperf.web.core.async.stream.StreamSender;
import io.springperf.web.core.async.stream.StreamSenderFactory;

/**
 * 把 {@code StreamingResponseBody} 的字节流接入既有 {@link StreamSender} 通道（与 SSE / Flux 同一条写出路径）。
 * <p>
 * 异步生命周期由 Callable 通道（{@code AsyncSupportRegistry#startCallableProcessing}）管理；本类只负责 「字节 → 帧」以及把结束信号交给
 * {@link StreamSender}：背压、终止块、HEAD 特判、断连清队列与错误关连接 全部继承自 {@code AbstractNettyStreamSender}，不在这里重复实现。
 * </p>
 * <p>
 * 响应头必须由调用方在绑定本 emitter 之前用 {@code flushChunked()} 提交（与 {@code ResponseBodyEmitterReturnValueResolver} 的
 * {@code extendResponseAndFlush(..., true)} 一致）： {@link StreamSender} 直接写 {@code DefaultHttpContent}，编码器必须已处于 chunked
 * 状态。
 * </p>
 */
public class ByteStreamEmitter extends StreamEmitter<byte[]> {

    /** 与 {@code NettyServerHttpResponse#streamCopy} 的读块保持一致。 */
    private static final int FRAME_SIZE = 8192;

    /**
     * 在途帧水位。{@link StreamSender} 的队列上限按 SSE 小事件设计（65536 条，对 8KB 帧等于 512MB）， 这里压到 32 帧 ≈ 256KB，避免慢客户端把整块堆内存吃满。
     */
    private static final int MAX_INFLIGHT_FRAMES = 32;

    private final PerfAsyncWebRequest asyncWebRequest;

    private final StreamSenderFactory senderFactory;

    private final boolean headRequest;

    private volatile StreamSender sender;

    public ByteStreamEmitter(PerfAsyncWebRequest asyncWebRequest, StreamSenderFactory senderFactory,
            boolean headRequest) {
        this.asyncWebRequest = asyncWebRequest;
        this.senderFactory = senderFactory;
        this.headRequest = headRequest;
    }

    @Override
    public void encode(Object data, OutputStream out) throws IOException {
        // 与 TextStreamEmitter#encode 同构：字节原样写入批量缓冲
        out.write((byte[]) data);
    }

    @Override
    protected void extendResponse(ServerHttpResponse response) {
        // 状态码/headers/Content-Type 已由 resolver 在 flushChunked 之前写好，此处无需扩展
    }

    @Override
    protected int getMaxFlushBytes() {
        return FRAME_SIZE;
    }

    /**
     * 提交一帧。HEAD 请求直接丢弃 body（响应头已由 flushChunked 以「仅响应头」写出）。
     * <p>
     * 传入的数组由调用方按帧复制，本方法入队后不再持有可变缓冲。
     * </p>
     */
    public void sendFrame(byte[] frame) throws IOException {
        if (headRequest) {
            return;
        }
        ensureSender();
        while (sender.queueSize() >= MAX_INFLIGHT_FRAMES) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("interrupted while waiting for stream backpressure");
            }
            LockSupport.parkNanos(1_000_000L);
        }
        send(frame);
    }

    /**
     * 正常结束。即便从未写出过字节也必须建 sender：chunked 头已提交，不写终止块客户端会挂到读超时。
     * <p>
     * HEAD 分支由 {@code onAllDataWritten} 的 {@code isCommitted && !isStreaming} 判定跳过终止块， 不会再补一个 {@code LastHttpContent}。
     * </p>
     */
    @Override
    public synchronized void complete() {
        if (complete.get()) {
            return;
        }
        // 必须先建 sender 再置完成标志：StreamEmitter#initialize 在 complete 已置位时会
        // 补调一次 sender.complete()（支持「先终止、后 initialize」的同步出错 Publisher），
        // 先置位会让终止块被写两次（第二次在编码器 INIT 态抛 unexpected LastHttpContent）。
        try {
            ensureSender();
        } catch (IOException e) {
            // 无可用 native response：已无通道可终结，交由响应超时兜底
            return;
        }
        if (!complete.compareAndSet(false, true)) {
            return;
        }
        sender.complete(false, null);
    }

    /** 异常结束：走 {@code onAllDataFailed} 语义——丢弃剩余帧并关闭连接，让客户端感知截断。 */
    public void completeWithError(Throwable ex) {
        try {
            ensureSender();
        } catch (Throwable ignored) {
            // 建 sender 失败（响应已结束 / 无 native response）：已无通道可终止
        }
        if (!complete.compareAndSet(false, true)) {
            return;
        }
        StreamSender current = this.sender;
        if (current != null) {
            current.complete(false, ex);
        }
    }

    private void ensureSender() throws IOException {
        if (sender != null) {
            return;
        }
        synchronized (this) {
            if (sender != null) {
                return;
            }
            StreamSender created = senderFactory.create(this, asyncWebRequest);
            initialize(created);
            this.sender = created;
        }
    }
}
