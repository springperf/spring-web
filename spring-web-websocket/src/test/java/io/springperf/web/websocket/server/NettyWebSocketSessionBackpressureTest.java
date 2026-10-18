package io.springperf.web.websocket.server;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketHandler;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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
     * 队列溢出的兜底策略是「关闭连接并上报」，不是「丢最旧帧」。
     * <p>
     * WebSocket 没有消息级重传，丢一帧等于业务状态不可恢复地分叉，且丢最旧帧时应用很可能已假设它送达； 断开连接才能让业务通过 handleTransportError 明确感知并决定补偿。
     * </p>
     */
    @Test
    void enqueueAndBackpressure_queueFull_closesConnectionAndNotifies() throws Exception {
        EmbeddedChannel channel = spy(new EmbeddedChannel());
        when(channel.isWritable()).thenReturn(false);
        WebSocketHandler handler = mock(WebSocketHandler.class);
        NettyWebSocketSession session = new NettyWebSocketSession(channel, URI.create("ws://localhost/ws"), null, null,
                null, null, null, handler);

        for (int i = 0; i <= 100; i++) { // 前 100 帧填满队列，第 101 帧触发溢出
            session.sendMessage(new BinaryMessage(ByteBuffer.wrap(new byte[] { (byte) i })));
        }
        channel.runPendingTasks();

        // 断言「close 被调用」而不是 channel.isActive()：Mockito spy 与真实 EmbeddedChannel 共享
        // pipeline/unsafe（见本文件 drainBackpressureQueue_stopsWhenBufferFillsAgain 的同类说明），
        // spy 上的 isActive() 会读到未同步的旧状态，断言会假失败。
        verify(channel).close();
        verify(handler).handleTransportError(eq(session), argThat(t -> t instanceof java.io.IOException));
        Queue<WebSocketFrame> queue = backpressureQueueOf(channel);
        assertTrue(queue.isEmpty(), "关闭时队列中未写出的帧必须全部释放");
        channel.finishAndReleaseAll();
    }

    /** 溢出必须让异步发送的 future 异常完成：调用方据此知道消息没发出去。 */
    @Test
    void sendMessageCompletion_backpressureOverflow_completesExceptionally() throws Exception {
        EmbeddedChannel channel = spy(new EmbeddedChannel());
        when(channel.isWritable()).thenReturn(false);
        NettyWebSocketSession session = new NettyWebSocketSession(channel, URI.create("ws://localhost/ws"), null, null,
                null, null, null, null);

        for (int i = 0; i < 100; i++) {
            session.sendMessage(new BinaryMessage(ByteBuffer.wrap(new byte[] { (byte) i })));
        }
        java.util.concurrent.CompletableFuture<Void> completion = session
                .sendMessageCompletion(new BinaryMessage(ByteBuffer.wrap(new byte[] { 9 })), false);
        channel.runPendingTasks();

        assertTrue(completion.isCompletedExceptionally(), "溢出时 future 必须异常完成，否则调用方以为发出去了");
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

        WsBackpressureQueue.drain(channel);

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

        WsBackpressureQueue.drain(channel);

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
        Field keyField = WsBackpressureQueue.class.getDeclaredField("QUEUE_KEY");
        keyField.setAccessible(true);
        return (AttributeKey<Queue<WebSocketFrame>>) keyField.get(null);
    }
}
