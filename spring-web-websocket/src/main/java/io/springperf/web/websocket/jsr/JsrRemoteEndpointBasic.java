package io.springperf.web.websocket.jsr;

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
     * {@code sendText}/{@code sendBinary} 的 {@code last} 参数透传：{@code false} 表示该分片不是消息的最后一段，
     * 由 {@link NettyWebSocketSession} 落成非 final 的 WebSocket 帧。流式发送（{@link #getSendStream()} /
     * {@link #getSendWriter()}）首期不支持。
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

    public JsrRemoteEndpointBasic(JsrWebSocketSession session, JsrCodecRegistry codecRegistry) {
        this.session = session;
        this.codecRegistry = codecRegistry;
    }

    @Override
    public void sendText(String text) throws IOException {
        session.getSpringSession().sendMessage(new TextMessage(text));
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException {
        session.getSpringSession().sendMessage(new BinaryMessage(data));
    }

    @Override
    public void sendText(String partialMessage, boolean isLast) throws IOException {
        session.getSpringSession().sendMessage(new TextMessage(partialMessage, isLast));
    }

    @Override
    public void sendBinary(ByteBuffer partialByte, boolean isLast) throws IOException {
        session.getSpringSession().sendMessage(new BinaryMessage(partialByte, isLast));
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
            session.getSpringSession().sendMessage(new TextMessage(payload.getText()));
        } else {
            session.getSpringSession().sendMessage(new BinaryMessage(payload.getBinary()));
        }
    }

    @Override
    public void setBatchingAllowed(boolean allowed) throws IOException {
        // 批处理首期忽略
    }

    @Override
    public boolean getBatchingAllowed() {
        return false;
    }

    @Override
    public void flushBatch() throws IOException {
        // 无批处理，无操作
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException, IllegalArgumentException {
        session.getSpringSession().sendMessage(new org.springframework.web.socket.PingMessage(applicationData));
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException, IllegalArgumentException {
        session.getSpringSession().sendMessage(new org.springframework.web.socket.PongMessage(applicationData));
    }
}
