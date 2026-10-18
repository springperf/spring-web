package io.springperf.web.websocket.jsr;

import jakarta.websocket.EncodeException;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * JSR-356 {@link RemoteEndpoint.Async} 实现。
 * <p>
 * 底层 {@link org.springframework.web.socket.WebSocketSession#sendMessage} 为同步写入 （EventLoop 串行化），因此此处以 CompletableFuture
 * 包装：发送成功即返回已完成的 future， 发送失败则返回已异常完成的 future。流式发送首期不支持。
 * </p>
 * <p>
 * 批处理（{@link #setBatchingAllowed(boolean)}）开启时，future/回调在帧被写入 outbound buffer 后即完成， 而非等待 flush —— flush 时机由调用方通过
 * {@link #flushBatch()} 决定。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.6
 */
public class JsrRemoteEndpointAsync implements RemoteEndpoint.Async {

    private final JsrWebSocketSession session;
    private final JsrCodecRegistry codecRegistry;
    /**
     * 发送超时（毫秒）。volatile：64 位原生类型在 32 位 JVM 上写非原子，且该值由业务线程设置、 可能被其他线程读取（与 JSR-356 {@code RemoteEndpoint.Async}
     * 的跨线程可见性语义一致）。
     */
    private volatile long sendTimeout = -1;

    /**
     * JSR-356 批处理开关。开启后 {@code sendXxx} 只把帧写入 Netty outbound buffer， 直到 {@link #flushBatch()}（或关闭批处理）才落网。
     * <p>
     * volatile：开关可能由非发送线程设置，而发送线程必须立刻看到新值。
     * </p>
     */
    private volatile boolean batchingAllowed;

    public JsrRemoteEndpointAsync(JsrWebSocketSession session, JsrCodecRegistry codecRegistry) {
        this.session = session;
        this.codecRegistry = codecRegistry;
    }

    @Override
    public long getSendTimeout() {
        return sendTimeout;
    }

    @Override
    public void setSendTimeout(long timeout) {
        this.sendTimeout = timeout;
    }

    @Override
    public void sendText(String text, SendHandler handler) {
        try {
            session.send(new TextMessage(text), !batchingAllowed);
            handler.onResult(new SendResult());
        } catch (IOException ex) {
            handler.onResult(new SendResult(ex));
        }
    }

    @Override
    public Future<Void> sendText(String text) {
        try {
            session.send(new TextMessage(text), !batchingAllowed);
            return CompletableFuture.completedFuture(null);
        } catch (IOException ex) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(ex);
            return f;
        }
    }

    @Override
    public Future<Void> sendBinary(ByteBuffer data) {
        try {
            session.send(new BinaryMessage(data), !batchingAllowed);
            return CompletableFuture.completedFuture(null);
        } catch (IOException ex) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(ex);
            return f;
        }
    }

    @Override
    public void sendBinary(ByteBuffer data, SendHandler handler) {
        try {
            session.send(new BinaryMessage(data), !batchingAllowed);
            handler.onResult(new SendResult());
        } catch (IOException ex) {
            handler.onResult(new SendResult(ex));
        }
    }

    @Override
    public Future<Void> sendObject(Object data) {
        try {
            JsrCodecRegistry.EncodedPayload payload = codecRegistry.encode(data, true);
            if (payload.isText()) {
                session.send(new TextMessage(payload.getText()), !batchingAllowed);
            } else {
                session.send(new BinaryMessage(payload.getBinary()), !batchingAllowed);
            }
            return CompletableFuture.completedFuture(null);
        } catch (IOException | EncodeException ex) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(ex);
            return f;
        }
    }

    @Override
    public void sendObject(Object data, SendHandler handler) {
        try {
            JsrCodecRegistry.EncodedPayload payload = codecRegistry.encode(data, true);
            if (payload.isText()) {
                session.send(new TextMessage(payload.getText()), !batchingAllowed);
            } else {
                session.send(new BinaryMessage(payload.getBinary()), !batchingAllowed);
            }
            handler.onResult(new SendResult());
        } catch (IOException | EncodeException ex) {
            handler.onResult(new SendResult(ex));
        }
    }

    @Override
    public void setBatchingAllowed(boolean allowed) throws IOException {
        if (batchingAllowed && !allowed) {
            // 由开转关：已写入但未 flush 的帧必须在返回前刷出
            session.flushBatch();
        }
        batchingAllowed = allowed;
    }

    @Override
    public boolean getBatchingAllowed() {
        return batchingAllowed;
    }

    @Override
    public void flushBatch() throws IOException {
        session.flushBatch();
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException, IllegalArgumentException {
        // 控制帧不受批处理影响：心跳滞留会触发对端空闲超时
        session.send(new org.springframework.web.socket.PingMessage(applicationData), true);
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException, IllegalArgumentException {
        session.send(new org.springframework.web.socket.PongMessage(applicationData), true);
    }
}
