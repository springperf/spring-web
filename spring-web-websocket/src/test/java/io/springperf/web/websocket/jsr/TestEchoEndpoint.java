package io.springperf.web.websocket.jsr;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JSR-356 娴嬭瘯鐢ㄥ洖鏄剧鐐癸細{@code @OnOpen/@OnMessage/@OnClose/@OnError} 鍏ㄧ敓鍛藉懆鏈熻褰曘€?
 */
@ServerEndpoint("/ws/jsr/{roomId}")
public class TestEchoEndpoint {

    /** 璁板綍姣忎釜杩炴帴瀹炰緥鏀跺埌鐨勬秷鎭笌璺緞鍙橀噺銆?*/
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
