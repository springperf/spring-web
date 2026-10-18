package io.springperf.web.websocket.server;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 回归 P2 并发组 #4：背压队列在通道关闭时必须释放未写出的帧。
 * <p>
 * 修复前 {@code closeFuture} 只 {@code clear()} 不释放引用，Binary/Text 帧持有的 ByteBuf 泄漏到连接生命周期之外。
 * </p>
 */
class NettyWebSocketSessionBackpressureTest {

    @Test
    void close_releasesFramesLeftInBackpressureQueue() throws Exception {
        EmbeddedChannel channel = spy(new EmbeddedChannel());
        // 强制通道不可写 → sendMessage 走背压队列而非直接 flush
        when(channel.isWritable()).thenReturn(false);

        NettyWebSocketSession session = new NettyWebSocketSession(channel, URI.create("ws://localhost/ws"), null, null,
                null, null, null, null);

        session.sendMessage(new BinaryMessage(ByteBuffer.wrap(new byte[] { 1, 2, 3 })));
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

    /**
     * 队列容量达到 100 时丢最旧一帧：不丢会让内存无界增长，丢了不 release 会让 ByteBuf 泄漏。 两条都必须成立，所以断言既看容量也看引用计数。
     */
    @Test
    void enqueueAndBackpressure_queueFull_dropsOldestAndReleases() throws Exception {
        EmbeddedChannel channel = spy(new EmbeddedChannel());
        when(channel.isWritable()).thenReturn(false);
        NettyWebSocketSession session = new NettyWebSocketSession(channel, URI.create("ws://localhost/ws"), null, null,
                null, null, null, null);

        session.sendMessage(new BinaryMessage(ByteBuffer.wrap(new byte[] { 0 })));
        channel.runPendingTasks();
        Queue<WebSocketFrame> queue = backpressureQueueOf(channel);
        WebSocketFrame oldest = queue.peek();
        assertEquals(1, oldest.refCnt());

        for (int i = 1; i < 100; i++) {
            session.sendMessage(new BinaryMessage(ByteBuffer.wrap(new byte[] { (byte) i })));
        }
        channel.runPendingTasks();

        assertEquals(99, queue.size(), "第 100 帧入队后必须丢弃最旧一帧，队列容量不超过阈值");
        assertEquals(0, oldest.refCnt(), "被丢弃的帧必须 release，否则 ByteBuf 泄漏");
        channel.finishAndReleaseAll();
    }

    @Test
    void enqueueAndBackpressure_unsupportedMessage_queuesNothing() throws Exception {
        // 未知消息类型 toFrame 返回 null：既不能入队，也不能 NPE
        EmbeddedChannel channel = spy(new EmbeddedChannel());
        when(channel.isWritable()).thenReturn(false);
        NettyWebSocketSession session = new NettyWebSocketSession(channel, URI.create("ws://localhost/ws"), null, null,
                null, null, null, null);

        session.sendMessage(new OpaqueMessage());
        channel.runPendingTasks();

        Queue<WebSocketFrame> queue = backpressureQueueOf(channel);
        assertTrue(queue == null || queue.isEmpty(), "无法转成帧的消息不应占用背压队列");
        channel.finishAndReleaseAll();
    }

    @Test
    void drainBackpressureQueue_presentButEmpty_noop() throws Exception {
        // 队列已创建但为空：应早退，不能进入循环
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(backpressureKey()).set(new LinkedList<>());

        NettyWebSocketSession.drainBackpressureQueue(channel);

        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void drainBackpressureQueue_stopsWhenBufferFillsAgain() throws Exception {
        // 排空过程中写缓冲再次满：必须跳出，把剩余帧留到下一次 writability 事件，
        // 否则会在不可写状态下持续堆积。
        // 用匿名子类覆写判定条件而非 Mockito spy：spy 的 pipeline 仍指向被代理的原对象，
        // writeAndFlush 的记录读不到，断言会假失败。
        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override
            public long bytesBeforeUnwritable() {
                return 0L;
            }
        };
        Queue<WebSocketFrame> queue = new LinkedList<>();
        queue.add(new TextWebSocketFrame("q1"));
        queue.add(new TextWebSocketFrame("q2"));
        channel.attr(backpressureKey()).set(queue);

        NettyWebSocketSession.drainBackpressureQueue(channel);

        assertEquals(1, queue.size(), "写缓冲再次满时应跳出循环，剩余帧留待下次事件");
        Object out = channel.readOutbound();
        assertTrue(out instanceof TextWebSocketFrame, "跳出前已写出的那一帧必须真的发出");
        io.netty.util.ReferenceCountUtil.release(out);
        while (!queue.isEmpty()) {
            queue.poll().release();
        }
        channel.finishAndReleaseAll();
    }

    @SuppressWarnings("unchecked")
    private static Queue<WebSocketFrame> backpressureQueueOf(Channel channel) throws Exception {
        return channel.attr(backpressureKey()).get();
    }

    @SuppressWarnings("unchecked")
    private static AttributeKey<Queue<WebSocketFrame>> backpressureKey() throws Exception {
        Field keyField = NettyWebSocketSession.class.getDeclaredField("BACKPRESSURE_QUEUE_KEY");
        keyField.setAccessible(true);
        return (AttributeKey<Queue<WebSocketFrame>>) keyField.get(null);
    }
}
