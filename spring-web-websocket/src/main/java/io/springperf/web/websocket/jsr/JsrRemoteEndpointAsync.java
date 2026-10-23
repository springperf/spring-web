package io.springperf.web.websocket.jsr;

import jakarta.websocket.EncodeException;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * JSR-356 {@link RemoteEndpoint.Async} 实现。
 * <p>
 * 返回的 future / {@link SendHandler} 回调绑定到帧真正写入完成的时刻（底层 {@code sendMessageCompletion}）： 发送成功即正常完成，失败即异常完成，语义与规范一致。
 * 流式发送只存在于 {@link RemoteEndpoint.Basic}（{@code getSendStream()} / {@code getSendWriter()}），本接口没有对应方法。
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
        session.sendAsync(new TextMessage(text), !batchingAllowed)
                .whenComplete((ignored, error) -> handler.onResult(toSendResult(error)));
    }

    @Override
    public Future<Void> sendText(String text) {
        return session.sendAsync(new TextMessage(text), !batchingAllowed);
    }

    /**
     * 异步发送二进制消息；完成时按规范把 {@code data} 的 position 推进到 limit（调用方在 future 完成后才能复用该 buffer）。
     */
    @Override
    public Future<Void> sendBinary(ByteBuffer data) {
        return session.sendAsync(new BinaryMessage(data), !batchingAllowed).whenComplete((ignored, error) -> {
            if (error == null) {
                session.markBufferConsumed(data);
            }
        });
    }

    @Override
    public void sendBinary(ByteBuffer data, SendHandler handler) {
        session.sendAsync(new BinaryMessage(data), !batchingAllowed).whenComplete((ignored, error) -> {
            if (error == null) {
                session.markBufferConsumed(data);
            }
            handler.onResult(toSendResult(error));
        });
    }

    @Override
    public Future<Void> sendObject(Object data) {
        try {
            return session.sendAsync(encodeMessage(data), !batchingAllowed);
        } catch (EncodeException ex) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }
    }

    @Override
    public void sendObject(Object data, SendHandler handler) {
        try {
            session.sendAsync(encodeMessage(data), !batchingAllowed)
                    .whenComplete((ignored, error) -> handler.onResult(toSendResult(error)));
        } catch (EncodeException ex) {
            handler.onResult(new SendResult(ex));
        }
    }

    /** POJO → 文本或二进制消息（编码失败按规范抛 {@link EncodeException}）。 */
    private WebSocketMessage<?> encodeMessage(Object data) throws EncodeException {
        JsrCodecRegistry.EncodedPayload payload = codecRegistry.encode(data, true);
        return payload.isText() ? new TextMessage(payload.getText()) : new BinaryMessage(payload.getBinary());
    }

    private static SendResult toSendResult(@Nullable Throwable error) {
        return error == null ? new SendResult() : new SendResult(error);
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
        JsrWebSocketSession.checkControlFramePayload(applicationData);
        // 控制帧不受批处理影响：心跳滞留会触发对端空闲超时
        session.send(new org.springframework.web.socket.PingMessage(applicationData), true);
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException, IllegalArgumentException {
        JsrWebSocketSession.checkControlFramePayload(applicationData);
        session.send(new org.springframework.web.socket.PongMessage(applicationData), true);
    }
}
