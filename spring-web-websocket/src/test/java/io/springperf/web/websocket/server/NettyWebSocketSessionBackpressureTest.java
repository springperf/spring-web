package io.springperf.web.websocket.server;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 回归 P2 并发组 #4：背压队列在通道关闭时必须释放未写出的帧。
 * <p>修复前 {@code closeFuture} 只 {@code clear()} 不释放引用，Binary/Text 帧持有的
 * ByteBuf 泄漏到连接生命周期之外。</p>
 */
class NettyWebSocketSessionBackpressureTest {

    @Test
    void close_releasesFramesLeftInBackpressureQueue() throws Exception {
        EmbeddedChannel channel = spy(new EmbeddedChannel());
        // 强制通道不可写 → sendMessage 走背压队列而非直接 flush
        when(channel.isWritable()).thenReturn(false);

        NettyWebSocketSession session = new NettyWebSocketSession(
                channel, URI.create("ws://localhost/ws"), null, null, null, null, null, null);

        session.sendMessage(new BinaryMessage(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        channel.runPendingTasks();

        Queue<WebSocketFrame> queue = backpressureQueueOf(channel);
        assertEquals(1, queue.size(), "通道不可写时消息应进入背压队列");
        WebSocketFrame pending = queue.peek();
        assertTrue(pending instanceof BinaryWebSocketFrame);
        assertEquals(1, pending.refCnt(), "入队帧引用计数应为 1");

        // 关闭通道 → closeFuture listener 应释放队列中未写出的帧
        channel.close();
        channel.runPendingTasks();

        assertTrue(queue.isEmpty(), "关闭后队列应被清空");
        assertEquals(0, pending.refCnt(), "关闭时必须释放未写出的帧，否则 ByteBuf 泄漏");
        channel.finishAndReleaseAll();
    }

    @SuppressWarnings("unchecked")
    private static Queue<WebSocketFrame> backpressureQueueOf(Channel channel) throws Exception {
        Field keyField = NettyWebSocketSession.class.getDeclaredField("BACKPRESSURE_QUEUE_KEY");
        keyField.setAccessible(true);
        AttributeKey<Queue<WebSocketFrame>> key = (AttributeKey<Queue<WebSocketFrame>>) keyField.get(null);
        return channel.attr(key).get();
    }
}
