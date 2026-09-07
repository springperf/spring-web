package io.springperf.example.realtime.controller;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;

/**
 * JSR-356锛圫ervlet WebSocket 瑙勮寖锛夌鐐圭ず渚嬶細`@ServerEndpoint` 娉ㄨВ鏂瑰紡銆?
 *
 * <p>鐢?{@code spring-web-websocket} 妯″潡鐨?JSR 妗ユ帴鑷姩鎵弿娉ㄥ唽锛?
 * 搴曞眰杩愯鍦ㄦ湰妗嗘灦鐨?Netty WebSocket 绠＄嚎涓娿€?/p>
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
