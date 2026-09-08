package io.springperf.web.websocket.jsr;

import javax.websocket.EncodeException;
import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * JSR-356 {@link RemoteEndpoint.Async} 瀹炵幇銆?
 *
 * <p>搴曞眰 {@link org.springframework.web.socket.WebSocketSession#sendMessage} 涓哄悓姝ュ啓鍏?
 * 锛圗ventLoop 涓茶鍖栵級锛屽洜姝ゆ澶勪互 CompletableFuture 鍖呰锛氬彂閫佹垚鍔熷嵆杩斿洖宸插畬鎴愮殑 future锛?
 * 鍙戦€佸け璐ュ垯杩斿洖宸插紓甯稿畬鎴愮殑 future銆傛祦寮忓彂閫侀鏈熶笉鏀寔銆?/p>
 *
 * @author huangcanda
 * @since 3.2.5
 */
public class JsrRemoteEndpointAsync implements RemoteEndpoint.Async {

    private final JsrWebSocketSession session;
    private final JsrCodecRegistry codecRegistry;
    private long sendTimeout = -1;

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
            session.getSpringSession().sendMessage(new TextMessage(text));
            handler.onResult(new SendResult());
        } catch (IOException ex) {
            handler.onResult(new SendResult(ex));
        }
    }

    @Override
    public Future<Void> sendText(String text) {
        try {
            session.getSpringSession().sendMessage(new TextMessage(text));
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
            session.getSpringSession().sendMessage(new BinaryMessage(data));
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
            session.getSpringSession().sendMessage(new BinaryMessage(data));
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
                session.getSpringSession().sendMessage(new TextMessage(payload.getText()));
            } else {
                session.getSpringSession().sendMessage(new BinaryMessage(payload.getBinary()));
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
                session.getSpringSession().sendMessage(new TextMessage(payload.getText()));
            } else {
                session.getSpringSession().sendMessage(new BinaryMessage(payload.getBinary()));
            }
            handler.onResult(new SendResult());
        } catch (IOException | EncodeException ex) {
            handler.onResult(new SendResult(ex));
        }
    }

    @Override
    public void setBatchingAllowed(boolean allowed) throws IOException {
        // 鎵瑰鐞嗛鏈熷拷鐣?
    }

    @Override
    public boolean getBatchingAllowed() {
        return false;
    }

    public void flushBatch() throws IOException {
        // 鏃犳壒澶勭悊锛屾棤鎿嶄綔
    }

    public void sendPing(ByteBuffer applicationData) throws IOException, IllegalArgumentException {
        session.getSpringSession().sendMessage(new org.springframework.web.socket.PingMessage(applicationData));
    }

    public void sendPong(ByteBuffer applicationData) throws IOException, IllegalArgumentException {
        session.getSpringSession().sendMessage(new org.springframework.web.socket.PongMessage(applicationData));
    }
}
