package io.springperf.web.websocket.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.*;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 将 Netty {@link Channel} 包装为 Spring {@link WebSocketSession}。
 *
 * @author huangcanda
 * @since 1.0.4
 */
@Slf4j
public class NettyWebSocketSession implements WebSocketSession {

    private static final AtomicLong sessionIdCounter = new AtomicLong(0);
    private static final AttributeKey<Queue<WebSocketFrame>> BACKPRESSURE_QUEUE_KEY =
            AttributeKey.valueOf("ws.backpressure.queue");

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

    public NettyWebSocketSession(Channel channel, URI uri, SpringHeadersAdapter handshakeHeaders,
                                  String acceptedProtocol, InetSocketAddress localAddress,
                                  InetSocketAddress remoteAddress, Principal principal,
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
        if (!isOpen()) {
            throw new IOException("Session " + id + " is closed");
        }
        // EventLoop 串行化 write，防止多线程并发导致帧交织
        if (channel.eventLoop().inEventLoop()) {
            doSendMessage(message);
        } else {
            channel.eventLoop().execute(() -> {
                try {
                    doSendMessage(message);
                } catch (Exception e) {
                    notifyTransportError(e);
                }
            });
        }
    }

    private void doSendMessage(WebSocketMessage<?> message) {
        if (!channel.isWritable()) {
            enqueueAndBackpressure(message);
            return;
        }
        flushMessage(message);
    }

    private void flushMessage(WebSocketMessage<?> message) {
        WebSocketFrame frame = toFrame(message);
        if (frame != null) {
            channel.writeAndFlush(frame)
                    .addListener((ChannelFutureListener) future -> {
                        if (!future.isSuccess() && handler != null) {
                            notifyTransportError(future.cause());
                        }
                    });
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
            return new TextWebSocketFrame(((TextMessage) message).getPayload());
        } else if (message instanceof BinaryMessage) {
            ByteBuffer buf = ((BinaryMessage) message).getPayload();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes));
        } else if (message instanceof PingMessage) {
            return new PingWebSocketFrame();
        } else if (message instanceof PongMessage) {
            return new PongWebSocketFrame();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void enqueueAndBackpressure(WebSocketMessage<?> message) {
        Queue<WebSocketFrame> queue = channel.attr(BACKPRESSURE_QUEUE_KEY).get();
        if (queue == null) {
            Queue<WebSocketFrame> newQueue = new LinkedList<>();
            channel.attr(BACKPRESSURE_QUEUE_KEY).set(newQueue);
            channel.closeFuture().addListener(f -> newQueue.clear());
            queue = newQueue;
        }
        WebSocketFrame frame = toFrame(message);
        if (frame != null) {
            queue.offer(frame);
        }
        if (queue.size() >= 100) {
            log.warn("WebSocket backpressure queue full (100), dropping oldest frame");
            WebSocketFrame dropped = queue.poll();
            if (dropped != null) dropped.release();
        }
    }

    /**
     * 排空背压队列。由 {@code BackpressureHandler} 在通道恢复可写时调用。
     */
    static void drainBackpressureQueue(Channel channel) {
        Queue<WebSocketFrame> queue = channel.attr(BACKPRESSURE_QUEUE_KEY).get();
        if (queue == null || queue.isEmpty()) return;
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

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
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
