package io.springperf.web.websocket.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final AttributeKey<Queue<WebSocketFrame>> BACKPRESSURE_QUEUE_KEY = AttributeKey
            .valueOf("ws.backpressure.queue");
    private static final String IDLE_HANDLER_NAME = "ws-idle";
    /** WebSocket 帧的 rsv 位：本项目不使用任何扩展协商，恒为 0。 */
    private static final int NO_RSV = 0;

    private final String id;
    private final Channel channel;
    private final URI uri;
    private final SpringHeadersAdapter handshakeHeaders;
    private final String acceptedProtocol;
    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;
    private final Principal principal;
    @Nullable
    private final WebSocketHandler handler;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    private int textMessageSizeLimit = 8 * 1024;
    private int binaryMessageSizeLimit = 64 * 1024;

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

    public NettyWebSocketSession(Channel channel, URI uri, SpringHeadersAdapter handshakeHeaders,
            String acceptedProtocol, InetSocketAddress localAddress, InetSocketAddress remoteAddress,
            Principal principal, @Nullable WebSocketHandler handler) {
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

    private void doSendMessage(WebSocketMessage<?> message, boolean flush) {
        if (!channel.isWritable()) {
            enqueueAndBackpressure(message);
            return;
        }
        WebSocketFrame frame = toFrame(message);
        if (frame == null) {
            return;
        }
        ChannelFuture future = flush ? channel.writeAndFlush(frame) : channel.write(frame);
        future.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess() && handler != null) {
                notifyTransportError(f.cause());
            }
        });
        if (!flush && !channel.isWritable()) {
            // 攒批把帧压进 outbound buffer，一旦越过高水位，后续帧只能进背压队列（满 100 帧丢最旧帧）。
            // 攒批场景下这条丢弃路径比逐条 flush 时容易命中得多，故到水位就提前刷出：
            // 批处理省的是 flush 次数，不能拿消息完整性去换。
            channel.flush();
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

    private void notifyTransportError(Throwable cause) {
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

    @Nullable
    private WebSocketFrame toFrame(WebSocketMessage<?> message) {
        if (message instanceof TextMessage) {
            // 透传 isLast：JSR-356 分片发送会用 last=false 标记非尾片，若一律按 final 帧写出，
            // 客户端会把每个分片当作独立完整消息，分片消息即被静默拆解破坏。
            return new TextWebSocketFrame(((TextMessage) message).isLast(), NO_RSV,
                    ((TextMessage) message).getPayload());
        } else if (message instanceof BinaryMessage) {
            // 用 duplicate() 读取：get(byte[]) 会推进 position，若直接读调用方的 buffer，
            // 同一个 ByteBuffer 发第二次就只剩 0 字节（静默丢消息，且不报错）。
            ByteBuffer buf = ((BinaryMessage) message).getPayload().duplicate();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return new BinaryWebSocketFrame(((BinaryMessage) message).isLast(), NO_RSV, Unpooled.wrappedBuffer(bytes));
        } else if (message instanceof PingMessage) {
            return new PingWebSocketFrame(copyControlPayload(((PingMessage) message).getPayload()));
        } else if (message instanceof PongMessage) {
            return new PongWebSocketFrame(copyControlPayload(((PongMessage) message).getPayload()));
        }
        return null;
    }

    /**
     * 拷贝 Ping/Pong 的应用数据（RFC 6455 §5.5.2/§5.5.3，控制帧载荷上限 125 字节）。
     * <p>
     * 必须拷贝而非用 {@code Unpooled.wrappedBuffer(ByteBuffer)} 共享：JSR 的 {@code sendPing/sendPong} 与 Spring 的
     * {@code PingMessage/PongMessage} 都不约束调用方在返回后不再改动 buffer，而帧要等 flush 才落网（批处理下更晚）， 共享内存会让调用方复用池化 buffer 时写坏待发数据。125
     * 字节以内的拷贝成本可忽略。
     * </p>
     * <p>
     * 修复前这里用无参构造，应用数据被静默丢弃。
     * </p>
     */
    private static ByteBuf copyControlPayload(ByteBuffer payload) {
        ByteBuffer source = payload.duplicate();
        if (!source.hasRemaining()) {
            return Unpooled.EMPTY_BUFFER;
        }
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return Unpooled.wrappedBuffer(bytes);
    }

    @SuppressWarnings("unchecked")
    private void enqueueAndBackpressure(WebSocketMessage<?> message) {
        Queue<WebSocketFrame> queue = channel.attr(BACKPRESSURE_QUEUE_KEY).get();
        if (queue == null) {
            // ArrayDeque 无逐帧 Node 分配、遍历局部性更好；队列本身只在 eventLoop 线程访问
            Queue<WebSocketFrame> newQueue = new ArrayDeque<>();
            channel.attr(BACKPRESSURE_QUEUE_KEY).set(newQueue);
            // 通道关闭时释放队列中未写出的帧（Binary/Text 帧持有 ByteBuf 引用）。
            // 仅 clear() 会泄漏引用；帧始终由 eventLoop 线程串行入队/排空，此处同样安全。
            channel.closeFuture().addListener(f -> {
                WebSocketFrame pending;
                while ((pending = newQueue.poll()) != null) {
                    pending.release();
                }
            });
            queue = newQueue;
        }
        WebSocketFrame frame = toFrame(message);
        if (frame != null) {
            queue.offer(frame);
        }
        if (queue.size() >= 100) {
            log.warn("WebSocket backpressure queue full (100), dropping oldest frame");
            WebSocketFrame dropped = queue.poll();
            if (dropped != null)
                dropped.release();
        }
    }

    /**
     * 排空背压队列。由 {@code BackpressureHandler} 在通道恢复可写时调用。
     */
    static void drainBackpressureQueue(Channel channel) {
        Queue<WebSocketFrame> queue = channel.attr(BACKPRESSURE_QUEUE_KEY).get();
        if (queue == null || queue.isEmpty())
            return;
        WebSocketFrame frame;
        while ((frame = queue.poll()) != null) {
            channel.writeAndFlush(frame);
            if (channel.bytesBeforeUnwritable() <= 0 && !queue.isEmpty()) {
                break; // 写缓冲区再次满时退出，等下一次 writability 事件
            }
        }
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
        return Collections.emptyList();
    }

    public void setOpen(boolean open) {
        this.open = open;
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
