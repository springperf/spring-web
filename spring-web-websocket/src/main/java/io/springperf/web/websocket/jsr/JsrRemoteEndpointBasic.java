package io.springperf.web.websocket.jsr;

import io.springperf.web.websocket.server.NettyWebSocketSession;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.websocket.RemoteEndpoint;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;

/**
 * JSR-356 {@link RemoteEndpoint.Basic} 实现，委托 Spring {@link WebSocketSession} 发送。
 * <p>
 * 消息体为 {@code String}（文本）、{@code ByteBuffer}（二进制）或 POJO（经 {@link Encoder} 编码，文本/二进制任选其一）。 分片发送通过
 * {@code sendText}/{@code sendBinary} 的 {@code last} 参数透传：{@code false} 表示该分片不是消息的最后一段， 由 {@link NettyWebSocketSession}
 * 落成非 final 的 WebSocket 帧。流式发送（{@link #getSendStream()} / {@link #getSendWriter()}）首期不支持。
 * </p>
 * <p>
 * 注意：{@code last=false} 的分片必须以 {@code last=true} 收尾，否则客户端会一直等待后续帧（WebSocket 语义如此）。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.6
 */
public class JsrRemoteEndpointBasic implements RemoteEndpoint.Basic {

    private final JsrWebSocketSession session;
    private final JsrCodecRegistry codecRegistry;

    /**
     * JSR-356 批处理开关。开启后 {@code sendXxx} 只把帧写入 Netty outbound buffer， 直到 {@link #flushBatch()}（或关闭批处理）才落网，
     * 多条小消息因此合并为一次系统调用。
     * <p>
     * volatile：开关可能由非发送线程设置，而发送线程必须立刻看到新值，否则「关闭批处理」后仍有消息被暂存。
     * </p>
     */
    private volatile boolean batchingAllowed;

    public JsrRemoteEndpointBasic(JsrWebSocketSession session, JsrCodecRegistry codecRegistry) {
        this.session = session;
        this.codecRegistry = codecRegistry;
    }

    @Override
    public void sendText(String text) throws IOException {
        session.send(new TextMessage(text), !batchingAllowed);
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException {
        session.send(new BinaryMessage(data), !batchingAllowed);
    }

    @Override
    public void sendText(String partialMessage, boolean isLast) throws IOException {
        session.send(new TextMessage(partialMessage, isLast), !batchingAllowed);
    }

    @Override
    public void sendBinary(ByteBuffer partialByte, boolean isLast) throws IOException {
        session.send(new BinaryMessage(partialByte, isLast), !batchingAllowed);
    }

    @Override
    public OutputStream getSendStream() throws IOException {
        throw new UnsupportedOperationException("Streaming send (getSendStream) is not supported");
    }

    @Override
    public Writer getSendWriter() throws IOException {
        throw new UnsupportedOperationException("Streaming send (getSendWriter) is not supported");
    }

    @Override
    public void sendObject(Object data) throws IOException, EncodeException {
        JsrCodecRegistry.EncodedPayload payload = codecRegistry.encode(data, true);
        if (payload.isText()) {
            session.send(new TextMessage(payload.getText()), !batchingAllowed);
        } else {
            session.send(new BinaryMessage(payload.getBinary()), !batchingAllowed);
        }
    }

    @Override
    public void setBatchingAllowed(boolean allowed) throws IOException {
        if (batchingAllowed && !allowed) {
            // 由开转关：已写入但未 flush 的帧必须在返回前刷出，否则会滞留到下一次 flush 或连接关闭
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
        // 控制帧不受批处理影响：ping/pong 直接影响对端保活与空闲检测，滞留到下一次 flush 会被当作心跳丢失
        session.send(new org.springframework.web.socket.PingMessage(applicationData), true);
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException, IllegalArgumentException {
        JsrWebSocketSession.checkControlFramePayload(applicationData);
        session.send(new org.springframework.web.socket.PongMessage(applicationData), true);
    }
}
