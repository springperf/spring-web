package io.springperf.example.realtime.controller;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;

/**
 * JSR-356（Servlet WebSocket 规范）端点示例：`@ServerEndpoint` 注解方式。
 *
 * <p>由 {@code spring-web-websocket} 模块的 JSR 桥接自动扫描注册，
 * 底层运行在本框架的 Netty WebSocket 管线上。</p>
 */
@Slf4j
@ServerEndpoint("/ws/jsr/{roomId}")
public class JsrChatEndpoint {

    @OnOpen
    public void onOpen(Session session, @PathParam("roomId") String roomId) {
        log.info("JSR-356 WS connected: session={}, roomId={}", session.getId(), roomId);
    }

    @OnMessage
    public String onMessage(String message, @PathParam("roomId") String roomId) {
        log.info("JSR-356 WS received from room {}: {}", roomId, message);
        return "echo:" + roomId + ":" + message;
    }

    @OnClose
    public void onClose(@PathParam("roomId") String roomId) {
        log.info("JSR-356 WS closed: roomId={}", roomId);
    }

    @OnError
    public void onError(Throwable error) {
        log.error("JSR-356 WS error", error);
    }
}
