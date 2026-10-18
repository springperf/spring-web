package io.springperf.web.websocket.server;

import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WsFrameCodec}：Spring 消息 → Netty 帧的转换分支与两个历史回归点。
 */
class WsFrameCodecTest {

    @Test
    void text_preservesPayloadAndLastFlag() {
        TextWebSocketFrame finalFrame = (TextWebSocketFrame) WsFrameCodec.encode(new TextMessage("hello"));
        assertEquals("hello", finalFrame.text());
        assertTrue(finalFrame.isFinalFragment());

        // JSR-356 分片发送用 last=false：若一律按 final 帧写出，客户端会把每个分片当独立消息
        TextWebSocketFrame partial = (TextWebSocketFrame) WsFrameCodec.encode(new TextMessage("part", false));
        assertEquals("part", partial.text());
        assertFalse(partial.isFinalFragment());
    }

    @Test
    void binary_copiesBytesWithoutAdvancingCallerBuffer() {
        ByteBuffer payload = ByteBuffer.wrap("bin".getBytes(StandardCharsets.UTF_8));
        BinaryWebSocketFrame frame = (BinaryWebSocketFrame) WsFrameCodec.encode(new BinaryMessage(payload));

        byte[] actual = new byte[frame.content().readableBytes()];
        frame.content().getBytes(frame.content().readerIndex(), actual);
        assertArrayEquals("bin".getBytes(StandardCharsets.UTF_8), actual);
        // 回归点：曾对调用方 buffer 直接 get(byte[])，同一 buffer 发第二次只剩 0 字节
        assertEquals(0, payload.position(), "调用方 ByteBuffer 的 position 不能被推进");
    }

    @Test
    void pingAndPong_copyApplicationData() {
        ByteBuffer pingPayload = ByteBuffer.wrap(new byte[] { 1, 2, 3 });
        PingWebSocketFrame ping = (PingWebSocketFrame) WsFrameCodec.encode(new PingMessage(pingPayload));
        assertEquals(3, ping.content().readableBytes());

        // 回归点：共享调用方 buffer 会在其被复用时写坏待发数据，故必须是拷贝
        pingPayload.put(0, (byte) 99);
        assertEquals(1, ping.content().getByte(0), "控制帧载荷必须是拷贝，不能是调用方 buffer 的视图");

        PongWebSocketFrame pong = (PongWebSocketFrame) WsFrameCodec
                .encode(new PongMessage(ByteBuffer.wrap(new byte[] { 7 })));
        assertEquals(1, pong.content().readableBytes());
        assertEquals(7, pong.content().getByte(0));
    }

    @Test
    void emptyControlPayload_usesEmptyBuffer() {
        PingWebSocketFrame ping = (PingWebSocketFrame) WsFrameCodec.encode(new PingMessage(ByteBuffer.allocate(0)));
        assertEquals(0, ping.content().readableBytes());
        assertTrue(ping.content().refCnt() >= 1, "空载荷也必须是可安全 release 的 buffer");
    }

    @Test
    void unsupportedMessageType_returnsNull() {
        assertNull(WsFrameCodec.encode(new OpaqueMessage()));
    }

    /** 非 Text/Binary/Ping/Pong 的未知消息类型（Spring 的 WebSocketMessage 允许自定义实现）。 */
    private static final class OpaqueMessage implements WebSocketMessage<Object> {

        @Override
        public Object getPayload() {
            return new Object();
        }

        @Override
        public int getPayloadLength() {
            return 0;
        }

        @Override
        public boolean isLast() {
            return true;
        }
    }
}
