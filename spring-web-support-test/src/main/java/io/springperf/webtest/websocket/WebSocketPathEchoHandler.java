package io.springperf.webtest.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket E2E 测试用 path variable echo handler。
 * 回显消息时附加路径变量 roomId 的值。
 */
@Slf4j
public class WebSocketPathEchoHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String roomId = (String) session.getAttributes().get("roomId");
        log.info("ws path handler connected: {}, roomId={}", session.getId(), roomId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String roomId = (String) session.getAttributes().get("roomId");
        String response = "room=" + roomId + " msg=" + message.getPayload();
        log.info("ws path handler echo: {}", response);
        session.sendMessage(new TextMessage(response));
    }
}
