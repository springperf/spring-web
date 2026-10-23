package io.springperf.web.websocket.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 将 Netty {@link Channel} 包装为 Spring {@link WebSocketSession}。
 *
 * @author huangcanda
 *
 * @since 1.0.4
 */
@Slf4j
public class NettyWebSocketSession implements WebSocketSession {

    private static final AtomicLong sessionIdCounter = new AtomicLong(0);
    private static final String IDLE_HANDLER_NAME = "ws-idle";

    private final String id;
    private final Channel channel;
    private final URI uri;
    private final HttpHeaders handshakeHeaders;
    private final String acceptedProtocol;
    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;
    private final Principal principal;
    @Nullable
    private final WebSocketHandler handler;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 握手协商成功的扩展；未协商时为不可变空列表。
     * <p>
     * volatile：握手在 EventLoop 上写入，而业务可能从其他线程查询（与 {@code getExtensions()} 的读语义一致）。
     * </p>
     */
    private volatile List<WebSocketExtension> extensions = Collections.emptyList();

    /** 默认文本消息上限（8KB），与 Spring WebSocket 的默认值一致。 */
    public static final int DEFAULT_TEXT_MESSAGE_SIZE_LIMIT = 8 * 1024;

    /** 默认二进制消息上限（64KB），与 Spring WebSocket 的默认值一致。 */
    public static final int DEFAULT_BINARY_MESSAGE_SIZE_LIMIT = 64 * 1024;

    private int textMessageSizeLimit = DEFAULT_TEXT_MESSAGE_SIZE_LIMIT;
    private int binaryMessageSizeLimit = DEFAULT_BINARY_MESSAGE_SIZE_LIMIT;

    private volatile boolean open = true;

    /**
     * 「已通知 afterConnectionClosed」的幂等标记。
     * <p>
     * 必须与 {@link #open} 分开：断连有四条路径（对端 Close 帧、服务端 {@link #close}、TCP 断开、 传输异常/空闲超时），它们到达的先后取决于网络与调度，任何一条先到都应通知、且只通知一次。
     * 若用 {@code isOpen()} 做守卫则一条都通知不了——Netty 保证 {@code channelInactive} 触发时 channel 已 inactive，而
     * {@code isOpen() = open && channel.isActive()} 恒为 false，见 WebSocketRoutingHandler#channelInactive。
     * </p>
     */
    private final AtomicBoolean closeNotified = new AtomicBoolean(false);

    public NettyWebSocketSession(Channel channel, URI uri, HttpHeaders handshakeHeaders, String acceptedProtocol,
            InetSocketAddress localAddress, InetSocketAddress remoteAddress, Principal principal,
            @Nullable WebSocketHandler handler) {
        this.id = "ws-" + sessionIdCounter.incrementAndGet();
        this.channel = channel;
        this.uri = uri;
        this.handshakeHeaders = handshakeHeaders;
        this.acceptedProtocol = acceptedProtocol;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.principal = principal;
        this.handler = handler;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public URI getUri() {
        return uri;
    }

    @Override
    public org.springframework.http.HttpHeaders getHandshakeHeaders() {
        return handshakeHeaders;
    }

    @Override
    public String getAcceptedProtocol() {
        return acceptedProtocol;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public Principal getPrincipal() {
        return principal;
    }

    @Override
    public boolean isOpen() {
        return open && channel.isActive();
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
        sendMessage(message, true);
    }

    /**
     * 发送消息，由调用方决定是否立即 flush。
     * <p>
     * {@code flush=false} 时帧只进入 Netty 的 outbound buffer，直到显式 {@link #flushBatch()}、 后续任意一次 flush 写或会话关闭（Close 帧自身
     * flush）才真正落网。JSR-356 的 {@code RemoteEndpoint.setBatchingAllowed(true)} 依赖该原语把多条小消息合并为一次系统调用。
     * </p>
     *
     * @param flush
     *            {@code true} 立即落网（默认语义）；{@code false} 暂存待批量刷出
     */
    public void sendMessage(WebSocketMessage<?> message, boolean flush) throws IOException {
        if (!isOpen()) {
            throw new IOException("Session " + id + " is closed");
        }
        // EventLoop 串行化 write，防止多线程并发导致帧交织
        if (channel.eventLoop().inEventLoop()) {
            doSendMessage(message, flush);
        } else {
            channel.eventLoop().execute(() -> {
                try {
                    doSendMessage(message, flush);
                } catch (Exception e) {
                    notifyTransportError(e);
                }
            });
        }
    }

    /**
     * 同步发送：返回时消息已交给传输层（{@code flush=true} 时已落网）。
     * <p>
     * JSR-356 {@code RemoteEndpoint.Basic} 的规范语义是「返回时整条消息已发出」，而 {@link #sendMessage} 从业务线程调用只入队即返回， 所以 Basic 走这条路径。
     * </p>
     * <p>
     * EventLoop 线程内不做等待：等待自己排队中的写任务会死锁，而此刻 {@code write} 已在同一线程内完成入队。
     * </p>
     */
    public void sendMessageAndWait(WebSocketMessage<?> message) throws IOException {
        sendMessageAndWait(message, true);
    }

    /**
     * 同步发送，由调用方决定是否立即 flush（批处理模式下 {@code flush=false}）。
     *
     * @throws IOException
     *             会话已关闭、EventLoop 已终止或写入失败
     */
    public void sendMessageAndWait(WebSocketMessage<?> message, boolean flush) throws IOException {
        if (channel.eventLoop().inEventLoop()) {
            if (!isOpen()) {
                throw new IOException("Session " + id + " is closed");
            }
            doSendMessage(message, flush);
            return;
        }
        awaitCompletion(sendMessageCompletion(message, flush));
    }

    /**
     * 异步发送，返回的 future 在帧真正写入后完成（失败时为异常完成）。
     * <p>
     * 供 JSR-356 {@code RemoteEndpoint.Async} 使用：规范要求二进制发送成功后把调用方 {@code ByteBuffer} 的 position 推进到 limit，
     * 这需要一个"发送完成"的信号。
     * </p>
     */
    public CompletableFuture<Void> sendMessageCompletion(WebSocketMessage<?> message, boolean flush) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        if (!isOpen()) {
            completion.completeExceptionally(new IOException("Session " + id + " is closed"));
            return completion;
        }
        if (channel.eventLoop().inEventLoop()) {
            doSendMessage(message, flush, completion);
        } else {
            try {
                channel.eventLoop().execute(() -> doSendMessage(message, flush, completion));
            } catch (RejectedExecutionException ex) {
                completion
                        .completeExceptionally(new IOException("Session " + id + " event loop rejected the send", ex));
            }
        }
        return completion;
    }

    private void awaitCompletion(CompletableFuture<Void> completion) throws IOException {
        try {
            completion.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending a WebSocket message on session " + id, ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Failed to send a WebSocket message on session " + id + ": " + cause.getMessage(),
                    cause);
        }
    }

    private void doSendMessage(WebSocketMessage<?> message, boolean flush) {
        doSendMessage(message, flush, null);
    }

    /**
     * 发送实现体：{@code completion} 非空时在帧写入后完成。
     * <p>
     * Spring 的异步发送路径传 {@code null}，避免每条消息都分配一个 {@link CompletableFuture}。
     * </p>
     */
    private void doSendMessage(WebSocketMessage<?> message, boolean flush,
            @Nullable CompletableFuture<Void> completion) {
        if (!channel.isWritable()) {
            Throwable overflow = enqueueAndBackpressure(message);
            if (overflow != null) {
                completeExceptionally(completion, overflow);
            } else {
                completeQuietly(completion);
            }
            return;
        }
        WebSocketFrame frame = WsFrameCodec.encode(message);
        if (frame == null) {
            completeQuietly(completion);
            return;
        }
        ChannelFuture future = flush ? channel.writeAndFlush(frame) : channel.write(frame);
        if (!flush) {
            // Netty 对未 flush 的 write，其 future 要到真正 flush 时才完成。批处理模式下「完成」的语义是
            // 「已写入 outbound buffer」（帧已拷贝、调用方的 buffer 已消费），若也去等 flush 完成，
            // 同步发送会一直阻塞到调用方自己 flush——那是死锁。发送失败仍由下面的 listener 通知业务。
            completeQuietly(completion);
        }
        future.addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                completeQuietly(completion);
                return;
            }
            if (handler != null) {
                notifyTransportError(f.cause());
            }
            completeExceptionally(completion, f.cause());
        });
        if (!flush && !channel.isWritable()) {
            // 攒批把帧压进 outbound buffer，一旦越过高水位，后续帧只能进背压队列。
            // 攒批场景下这条路径比逐条 flush 时容易命中得多，故到水位就提前刷出：
            // 批处理省的是 flush 次数，不能拿消息完整性去换。
            channel.flush();
        }
    }

    private static void completeQuietly(@Nullable CompletableFuture<Void> completion) {
        if (completion != null) {
            completion.complete(null);
        }
    }

    private static void completeExceptionally(@Nullable CompletableFuture<Void> completion, Throwable cause) {
        if (completion != null) {
            completion.completeExceptionally(cause);
        }
    }

    /**
     * 刷出当前累积在 outbound buffer 中的帧（JSR-356 {@code RemoteEndpoint#flushBatch()} 的底层原语）。
     * <p>
     * 非 EventLoop 线程调用时投递到 EventLoop 执行，与 {@link #sendMessage} 走同一条 FIFO 投递队列， 因此不会出现「flush 抢在其前的 write 之前执行」而漏刷的情况。
     * </p>
     */
    public void flushBatch() {
        // 通道已关闭：outbound buffer 里的帧不会再落网，且 EventLoop 可能已终止（execute 会抛
        // RejectedExecutionException，把 unchecked 异常扔给业务线程）。注意判的是 channel 活性而非
        // session 的 open 标志——关闭握手期间仍需把已暂存的帧刷出去。
        if (!channel.isActive()) {
            return;
        }
        if (channel.eventLoop().inEventLoop()) {
            channel.flush();
        } else {
            channel.eventLoop().execute(channel::flush);
        }
    }

    /** 把传输层失败转交业务 handler；无 handler 时降级为 WARN（握手前构造的诊断用会话）。 */
    void notifyTransportError(Throwable cause) {
        if (handler != null) {
            try {
                handler.handleTransportError(this, cause);
            } catch (Exception ex) {
                log.error("handleTransportError failed on session {}", id, ex);
            }
        } else {
            log.warn("Write failed on session {}: {}", id, cause.getMessage(), cause);
        }
    }

    /**
     * 通道不可写时把消息排入背压队列（队列本身见 {@link WsBackpressureQueue}）。
     *
     * @return {@code null} 表示已入队；非 {@code null} 表示队列溢出（连接已被关闭），值是应上报给业务的失败原因
     */
    @Nullable
    private Throwable enqueueAndBackpressure(WebSocketMessage<?> message) {
        return WsBackpressureQueue.enqueue(this, message);
    }

    @Override
    public void close(CloseStatus status) throws IOException {
        open = false;
        // writeAndFlush 会连带刷出批处理中暂存的帧，保证关闭前已发送但未 flush 的消息不丢失
        channel.writeAndFlush(new CloseWebSocketFrame(status.getCode(), status.getReason()))
                .addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void close() throws IOException {
        close(CloseStatus.NORMAL);
    }

    @Override
    public List<WebSocketExtension> getExtensions() {
        return extensions;
    }

    /**
     * 握手完成时由路由层写入协商成功的扩展（如 permessage-deflate）。
     * <p>
     * 用 setter 而不是构造器参数：构造器是公开 API，加参数会破坏既有调用方（二进制兼容）。
     * </p>
     */
    public void setExtensions(List<WebSocketExtension> extensions) {
        this.extensions = extensions == null ? Collections.emptyList() : extensions;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    /** 包内访问器：背压队列挂在 channel attribute 上，需要拿到通道本身。 */
    Channel channel() {
        return channel;
    }

    /**
     * 尝试标记「已通知关闭」。返回 true 表示本次调用赢得了通知权，调用方应触发 {@code afterConnectionClosed}； 返回 false 表示其他路径已通知过，调用方必须跳过。
     * <p>
     * 这是断连通知的唯一入口：对端 Close 帧、服务端 {@link #close}、TCP 断开、传输异常/空闲超时 四条路径都经由它，谁先到谁通知，后到的静默退出，从而保证恰好一次。
     * </p>
     */
    public boolean markCloseNotified() {
        return closeNotified.compareAndSet(false, true);
    }

    public boolean isCloseNotified() {
        return closeNotified.get();
    }

    @Override
    public int getTextMessageSizeLimit() {
        return textMessageSizeLimit;
    }

    @Override
    public void setTextMessageSizeLimit(int limit) {
        this.textMessageSizeLimit = limit;
    }

    @Override
    public int getBinaryMessageSizeLimit() {
        return binaryMessageSizeLimit;
    }

    @Override
    public void setBinaryMessageSizeLimit(int limit) {
        this.binaryMessageSizeLimit = limit;
    }

    /**
     * 运行期更新会话级空闲超时（毫秒），传播到 Netty pipeline 中的 {@link IdleStateHandler}：
     * <ul>
     * <li>已存在则原位置 replace 为新 {@link IdleStateHandler} 实例重新调度（Netty 4.1 的 IdleStateHandler 无运行期 setter）；</li>
     * <li>不存在且 {@code idleTimeoutMs > 0} 则新增（握手时全局/per-path 空闲超时未启用的场景）；</li>
     * <li>{@code <= 0} 则移除已有的空闲检测。</li>
     * </ul>
     * 操作提交到 channel 的 eventLoop 执行，使 JSR-356 {@code Session.setMaxIdleTimeout} 真正生效 （修复前仅赋值字段、空闲超时空操作）。
     */
    public void setMaxIdleTimeout(long idleTimeoutMs) {
        channel.eventLoop().execute(() -> {
            IdleStateHandler idle = channel.pipeline().get(IdleStateHandler.class);
            if (idleTimeoutMs <= 0) {
                if (idle != null) {
                    channel.pipeline().remove(idle);
                }
                return;
            }
            if (idle != null) {
                // Netty 4.1 的 IdleStateHandler 仅提供 getter、无运行期 set 方法；超时在
                // handlerAdded 时由 initialize() 固化。故就地 replace 为同位置新实例以重新调度。
                channel.pipeline().replace(idle, IDLE_HANDLER_NAME,
                        new IdleStateHandler(idleTimeoutMs, idleTimeoutMs, idleTimeoutMs, TimeUnit.MILLISECONDS));
            } else {
                channel.pipeline().addFirst(IDLE_HANDLER_NAME,
                        new IdleStateHandler(idleTimeoutMs, idleTimeoutMs, idleTimeoutMs, TimeUnit.MILLISECONDS));
            }
        });
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj instanceof NettyWebSocketSession) {
            return id.equals(((NettyWebSocketSession) obj).id);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "NettyWebSocketSession[id=" + id + ", uri=" + uri + "]";
    }
}
