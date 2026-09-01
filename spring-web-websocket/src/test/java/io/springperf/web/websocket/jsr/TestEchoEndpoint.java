package io.springperf.web.websocket.jsr;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JSR-356 测试用回显端点：{@code @OnOpen/@OnMessage/@OnClose/@OnError} 全生命周期记录。
 */
@ServerEndpoint("/ws/jsr/{roomId}")
public class TestEchoEndpoint {

    /** 记录每个连接实例收到的消息与路径变量。 */
    public static final CopyOnWriteArrayList<String> MESSAGES = new CopyOnWriteArrayList<>();
    public static final CopyOnWriteArrayList<String> OPEN_ROOM_IDS = new CopyOnWriteArrayList<>();
    public static final AtomicReference<String> CLOSE_ROOM_ID = new AtomicReference<>();
    public static final AtomicInteger ON_ERROR_COUNT = new AtomicInteger();

    @OnOpen
    public void onOpen(Session session, @PathParam("roomId") String roomId) {
        OPEN_ROOM_IDS.add(roomId);
    }

    @OnMessage
    public String onMessage(String message, @PathParam("roomId") String roomId) {
        MESSAGES.add(roomId + ":" + message);
        return "echo:" + roomId + ":" + message;
    }

    @OnClose
    public void onClose(@PathParam("roomId") String roomId) {
        CLOSE_ROOM_ID.set(roomId);
    }

    @OnError
    public void onError(Throwable error) {
        ON_ERROR_COUNT.incrementAndGet();
    }
}
