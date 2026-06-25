package io.springperf.webtest.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;

/**
 * WebSocket E2E 测试用 echo handler。
 * 文本和二进制消息均直接回显。
 */
@Slf4j
public class WebSocketEchoHandler extends AbstractWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("ws echo handler connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.info("ws echo received text: {}", message.getPayload());
        session.sendMessage(new TextMessage(message.getPayload()));
        log.info("ws echo sent text: {}", message.getPayload());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ByteBuffer buf = message.getPayload();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        log.info("ws echo received binary: {} bytes", bytes.length);
        session.sendMessage(new BinaryMessage(ByteBuffer.wrap(bytes)));
        log.info("ws echo sent binary: {} bytes", bytes.length);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("ws echo handler closed: {}, status={}", session.getId(), status);
    }
}
