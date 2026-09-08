package io.springperf.web.websocket.jsr;

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.RemoteEndpoint;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;

/**
 * JSR-356 {@link RemoteEndpoint.Basic} 实现，委托 Spring {@link WebSocketSession} 发送。
 *
 * <p>消息体为 {@code String}（文本）、{@code ByteBuffer}（二进制）或 POJO（经
 * {@link Encoder} 编码，文本/二进制任选其一）。流式发送（{@link #getSendStream()}
 * / {@link #getSendWriter()}）与分片（last 参数）首期不支持。</p>
 *
 * @author huangcanda
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
        sendText(partialMessage);
    }

    @Override
    public void sendBinary(ByteBuffer partialByte, boolean isLast) throws IOException {
        sendBinary(partialByte);
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

    public void sendPing(ByteBuffer applicationData) throws IOException, IllegalArgumentException {
        session.getSpringSession().sendMessage(new org.springframework.web.socket.PingMessage(applicationData));
    }

    public void sendPong(ByteBuffer applicationData) throws IOException, IllegalArgumentException {
        session.getSpringSession().sendMessage(new org.springframework.web.socket.PongMessage(applicationData));
    }
}
