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
 * JSR-356 {@link RemoteEndpoint.Basic} 瀹炵幇锛屽鎵?Spring {@link WebSocketSession} 鍙戦€併€?
 *
 * <p>娑堟伅浣撲负 {@code String}锛堟枃鏈級銆亄@code ByteBuffer}锛堜簩杩涘埗锛夋垨 POJO锛堢粡
 * {@link Encoder} 缂栫爜锛屾枃鏈?浜岃繘鍒朵换閫夊叾涓€锛夈€傛祦寮忓彂閫侊紙{@link #getSendStream()}
 * / {@link #getSendWriter()}锛変笌鍒嗙墖锛坙ast 鍙傛暟锛夐鏈熶笉鏀寔銆?/p>
 *
 * @author huangcanda
 * @since 3.2.5
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
        // 鎵瑰鐞嗛鏈熷拷鐣?
    }

    @Override
    public boolean getBatchingAllowed() {
        return false;
    }

    @Override
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
