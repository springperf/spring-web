package io.springperf.web.websocket.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link NettyWebSocketSession} 的消息发送类型转换、关闭、访问器与值语义。
 */
class NettyWebSocketSessionTest {

    private URI uri = URI.create("ws://localhost:8080/ws");

    private NettyWebSocketSession newSession(EmbeddedChannel channel) {
        return new NettyWebSocketSession(channel, uri,
                new SpringHeadersAdapter(new io.netty.handler.codec.http.DefaultHttpHeaders(false)), "chat",
                new InetSocketAddress("127.0.0.1", 8080), new InetSocketAddress("192.168.1.5", 5000), null, null);
    }

    @Test
    void sendNonLastFragment_writesNonFinalFrame() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        session.sendMessage(new TextMessage("part", false));
        channel.runPendingTasks();

        WebSocketFrame out = channel.readOutbound();
        assertTrue(out instanceof TextWebSocketFrame);
        assertFalse(out.isFinalFragment(), "未结尾的分片必须落成非 final 帧，否则客户端会当作完整消息");
        out.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void sendBinaryNonLastFragment_writesNonFinalFrame() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        session.sendMessage(new BinaryMessage(ByteBuffer.wrap(new byte[] { 1, 2 }), false));
        channel.runPendingTasks();

        WebSocketFrame out = channel.readOutbound();
        assertTrue(out instanceof BinaryWebSocketFrame);
        assertFalse(out.isFinalFragment(), "二进制分片的 final 位同样必须透传");
        out.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void sendTextMessage_writesTextFrame() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        session.sendMessage(new TextMessage("hello"));
        channel.runPendingTasks();

        WebSocketFrame out = channel.readOutbound();
        assertTrue(out instanceof TextWebSocketFrame);
        assertEquals("hello", ((TextWebSocketFrame) out).text());
        out.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void sendMessageWithoutFlush_defersFrameUntilFlushBatch() throws Exception {
        // 批处理原语：flush=false 只写 outbound buffer，必须由 flushBatch() 显式刷出
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        session.sendMessage(new TextMessage("batched"), false);
        channel.runPendingTasks();

        assertNull(channel.readOutbound(), "flush=false 的帧不得立即落网");

        session.flushBatch();
        WebSocketFrame out = channel.readOutbound();
        assertTrue(out instanceof TextWebSocketFrame);
        assertEquals("batched", ((TextWebSocketFrame) out).text());
        out.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void flushBatch_fromNonEventLoop_defersToEventLoop() {
        // 业务线程调用时必须投递到 EventLoop：直接 flush 会与仍在排队中的 write 乱序，导致漏刷
        Channel channel = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        when(channel.eventLoop()).thenReturn(loop);
        when(channel.isActive()).thenReturn(true);
        when(loop.inEventLoop()).thenReturn(false);

        NettyWebSocketSession session = new NettyWebSocketSession(channel, uri, null, null, null, null, null, null);
        session.flushBatch();

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(loop).execute(task.capture());
        verify(channel, never()).flush();

        task.getValue().run();
        verify(channel).flush();
    }

    @Test
    void flushBatch_onInactiveChannel_skipsEventLoop() {
        // 通道已断且 EventLoop 可能已终止：此时 execute 会抛 RejectedExecutionException，
        // 而该 unchecked 异常会直接抛给业务线程（JSR 的 flushBatch 只声明 IOException）
        Channel channel = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        when(channel.eventLoop()).thenReturn(loop);
        when(channel.isActive()).thenReturn(false);
        when(loop.inEventLoop()).thenReturn(false);

        NettyWebSocketSession session = new NettyWebSocketSession(channel, uri, null, null, null, null, null, null);
        assertDoesNotThrow(session::flushBatch);

        verify(loop, never()).execute(any(Runnable.class));
        verify(channel, never()).flush();
    }

    @Test
    void sendWithoutFlush_crossingHighWatermark_flushesEarly() throws Exception {
        // 攒批把帧压进 outbound buffer：越过高水位后后续帧只能进背压队列（满 100 帧丢最旧帧）。
        // 必须到水位就提前 flush，否则批处理会引入「静默丢消息」这条新路径。
        Channel channel = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        ChannelFuture future = mock(ChannelFuture.class);
        when(channel.eventLoop()).thenReturn(loop);
        when(loop.inEventLoop()).thenReturn(true);
        when(channel.isActive()).thenReturn(true);
        // 入口可写，写入后越过高水位
        when(channel.isWritable()).thenReturn(true, false);
        when(channel.write(any(WebSocketFrame.class))).thenReturn(future);

        NettyWebSocketSession session = new NettyWebSocketSession(channel, uri, null, null, null, null, null, null);
        session.sendMessage(new TextMessage("x"), false);

        ArgumentCaptor<WebSocketFrame> written = ArgumentCaptor.forClass(WebSocketFrame.class);
        verify(channel).write(written.capture());
        written.getValue().release();
        verify(channel, never()).writeAndFlush(any(WebSocketFrame.class));
        verify(channel).flush();
    }

    @Test
    void flushBatch_onClosedSession_doesNotThrow() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        session.setOpen(false);

        session.flushBatch();
        channel.finishAndReleaseAll();
    }

    @Test
    void sendBinaryMessage_writesBinaryFrame() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        ByteBuffer payload = ByteBuffer.wrap(new byte[] { 1, 2, 3 });
        session.sendMessage(new BinaryMessage(payload));
        channel.runPendingTasks();

        WebSocketFrame out = channel.readOutbound();
        assertTrue(out instanceof BinaryWebSocketFrame);
        assertArrayEquals(new byte[] { 1, 2, 3 }, io.netty.buffer.ByteBufUtil.getBytes(out.content()));
        out.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void sendBinaryMessage_twiceWithSameBuffer_sendsPayloadBothTimes() throws Exception {
        // 回归：toFrame 曾直接对调用方的 ByteBuffer 做 get(byte[])，会推进 position。
        // 同一个 buffer 发第二次就只剩 0 字节——静默丢消息且不报错，复用池化 buffer 的调用方必踩。
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        ByteBuffer payload = ByteBuffer.wrap(new byte[] { 1, 2, 3 });

        session.sendMessage(new BinaryMessage(payload));
        session.sendMessage(new BinaryMessage(payload));
        channel.runPendingTasks();

        WebSocketFrame first = channel.readOutbound();
        WebSocketFrame second = channel.readOutbound();
        assertArrayEquals(new byte[] { 1, 2, 3 }, io.netty.buffer.ByteBufUtil.getBytes(first.content()));
        assertArrayEquals(new byte[] { 1, 2, 3 }, io.netty.buffer.ByteBufUtil.getBytes(second.content()), "第二次发送不得变空");
        assertEquals(0, payload.position(), "发送不得消耗调用方的 ByteBuffer");
        first.release();
        second.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void sendPingPong_writesFrames() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        session.sendMessage(new PingMessage());
        session.sendMessage(new PongMessage());
        channel.runPendingTasks();

        WebSocketFrame f1 = channel.readOutbound();
        WebSocketFrame f2 = channel.readOutbound();
        assertTrue(f1 instanceof PingWebSocketFrame);
        assertTrue(f2 instanceof PongWebSocketFrame);
        f1.release();
        f2.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void sendMessage_whenClosed_throws() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        session.setOpen(false);
        assertThrows(java.io.IOException.class, () -> session.sendMessage(new TextMessage("x")));
        channel.finishAndReleaseAll();
    }

    @Test
    void close_writesCloseFrameAndClosesChannel() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        assertTrue(session.isOpen());
        session.close(CloseStatus.NORMAL);
        channel.runPendingTasks();
        assertFalse(session.isOpen());
        WebSocketFrame out = channel.readOutbound();
        assertTrue(out instanceof CloseWebSocketFrame);
        out.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void getters_exposeMetadata() {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        assertEquals(uri, session.getUri());
        assertNotNull(session.getId());
        assertEquals("chat", session.getAcceptedProtocol());
        assertNotNull(session.getHandshakeHeaders());
        assertNull(session.getPrincipal());
        assertTrue(session.getExtensions().isEmpty());
        assertEquals("127.0.0.1", session.getLocalAddress().getHostString());
        assertEquals(8080, session.getLocalAddress().getPort());
        assertEquals("192.168.1.5", session.getRemoteAddress().getHostString());
        channel.finishAndReleaseAll();
    }

    @Test
    void sizeLimits_areSettable() {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        session.setTextMessageSizeLimit(16384);
        session.setBinaryMessageSizeLimit(131072);
        assertEquals(16384, session.getTextMessageSizeLimit());
        assertEquals(131072, session.getBinaryMessageSizeLimit());
        channel.finishAndReleaseAll();
    }

    @Test
    void attributes_areMutable() {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        session.getAttributes().put("key", "value");
        assertEquals("value", session.getAttributes().get("key"));
        channel.finishAndReleaseAll();
    }

    @Test
    void equals_hashCode_basedOnId() {
        EmbeddedChannel c1 = new EmbeddedChannel();
        EmbeddedChannel c2 = new EmbeddedChannel();
        NettyWebSocketSession s1 = newSession(c1);
        NettyWebSocketSession s2 = newSession(c2);
        assertNotEquals(s1, s2);
        assertEquals(s1, s1);
        assertEquals(s1.hashCode(), s1.hashCode());
        assertNotEquals(s1, "not-a-session");
        assertNotEquals(s1, null);
        c1.finishAndReleaseAll();
        c2.finishAndReleaseAll();
    }

    @Test
    void closeDefault_writesNormalCloseFrame() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        session.close();
        channel.runPendingTasks();
        assertFalse(session.isOpen());
        WebSocketFrame out = channel.readOutbound();
        assertTrue(out instanceof CloseWebSocketFrame);
        out.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void toString_describesSession() {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        assertTrue(session.toString().contains("ws-"));
        assertTrue(session.toString().contains("/ws"));
        channel.finishAndReleaseAll();
    }

    @Test
    void drainBackpressureQueue_flushesQueuedFrames() {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        // 通过反射拿到私有 handler 并非必要——直接触发 enqueue 需要通道不可写，此处改为
        // 使用 channel attr 手动注入队列，验证 drain 逻辑本身。
        java.util.Queue<io.netty.handler.codec.http.websocketx.WebSocketFrame> queue = new java.util.LinkedList<>();
        queue.add(new TextWebSocketFrame("q1"));
        queue.add(new TextWebSocketFrame("q2"));
        channel.attr(
                io.netty.util.AttributeKey.<java.util.Queue<io.netty.handler.codec.http.websocketx.WebSocketFrame>> valueOf(
                        "ws.backpressure.queue"))
                .set(queue);

        NettyWebSocketSession.drainBackpressureQueue(channel);
        channel.runPendingTasks();

        assertTrue(queue.isEmpty(), "drain 应清空背压队列");
        Object out = channel.readOutbound();
        assertNotNull(out);
        io.netty.util.ReferenceCountUtil.release(out);
        channel.finishAndReleaseAll();
    }

    @Test
    void drainBackpressureQueue_emptyQueue_noop() {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession.drainBackpressureQueue(channel);
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    // ========== M7：setMaxIdleTimeout 应真正传播到 pipeline 的 IdleStateHandler ==========

    @Test
    void setMaxIdleTimeout_updatesExistingIdleHandler() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("ws-idle", new IdleStateHandler(0, 0, 0));

        NettyWebSocketSession session = newSession(channel);
        session.setMaxIdleTimeout(30000);
        channel.runPendingTasks();

        IdleStateHandler after = channel.pipeline().get(IdleStateHandler.class);
        assertNotNull(after, "IdleStateHandler 应存在");
        assertEquals(30000, after.getAllIdleTimeInMillis(), "应更新现有 IdleStateHandler 的空闲超时");
        channel.finishAndReleaseAll();
    }

    @Test
    void setMaxIdleTimeout_addsIdleHandlerWhenAbsent() {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        session.setMaxIdleTimeout(30000);
        channel.runPendingTasks();

        IdleStateHandler after = channel.pipeline().get(IdleStateHandler.class);
        assertNotNull(after, "无 IdleStateHandler 时应新增");
        assertEquals(30000, after.getAllIdleTimeInMillis());
        channel.finishAndReleaseAll();
    }

    @Test
    void setMaxIdleTimeout_removesIdleHandlerWhenZero() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("ws-idle", new IdleStateHandler(60000, 60000, 60000));
        NettyWebSocketSession session = newSession(channel);
        session.setMaxIdleTimeout(0);
        channel.runPendingTasks();

        assertNull(channel.pipeline().get(IdleStateHandler.class), "setMaxIdleTimeout(<=0) 应移除空闲检测");
        channel.finishAndReleaseAll();
    }

    @Test
    void setMaxIdleTimeout_zeroWithoutIdleHandler_noop() {
        // <=0 时 pipeline 里本来就没有空闲检测：应静默返回，不能 NPE
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);

        assertDoesNotThrow(() -> session.setMaxIdleTimeout(0));
        channel.runPendingTasks();

        assertNull(channel.pipeline().get(IdleStateHandler.class));
        channel.finishAndReleaseAll();
    }

    // ========== 生命周期标记 ==========

    @Test
    void markCloseNotified_grantsExactlyOnce() {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);

        assertFalse(session.isCloseNotified(), "初始状态不应标记为已通知");
        assertTrue(session.markCloseNotified(), "首个调用者应赢得通知权");
        assertTrue(session.isCloseNotified());
        assertFalse(session.markCloseNotified(), "重复调用必须返回 false，否则 afterConnectionClosed 会重复触发");
        channel.finishAndReleaseAll();
    }

    @Test
    void isOpen_falseWhenChannelInactive() {
        // open 仍为 true 但通道已断：isOpen() = open && channel.isActive()，后半段决定结果。
        // 这也是不能用 isOpen() 把关 afterConnectionClosed 的原因（channelInactive 时它恒为 false）。
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);

        channel.close();

        assertFalse(session.isOpen(), "通道断开后 isOpen 必须为 false");
    }

    // ========== 未知消息类型 ==========

    @Test
    void sendMessage_unsupportedType_writesNothing() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);

        session.sendMessage(new OpaqueMessage());
        channel.runPendingTasks();

        assertNull(channel.readOutbound(), "未知消息类型不应产生任何帧，也不能抛异常");
        channel.finishAndReleaseAll();
    }

    // ========== 跨线程发送：EventLoop 串行化 ==========

    /**
     * 非 EventLoop 线程调用 sendMessage 必须把写操作移交到 eventLoop。 直接在调用线程 write 会让多线程并发写导致帧交织（分片互相穿插）。
     */
    @Test
    void sendMessage_fromNonEventLoopThread_handsOffToEventLoop() throws Exception {
        Channel channel = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        ChannelFuture future = mock(ChannelFuture.class);
        when(channel.eventLoop()).thenReturn(loop);
        when(channel.isActive()).thenReturn(true);
        when(channel.isWritable()).thenReturn(true);
        when(loop.inEventLoop()).thenReturn(false);
        when(channel.writeAndFlush(any(WebSocketFrame.class))).thenReturn(future);

        NettyWebSocketSession session = new NettyWebSocketSession(channel, uri, null, null, null, null, null, null);
        session.sendMessage(new TextMessage("off-loop"));

        verify(channel, never()).writeAndFlush(any(WebSocketFrame.class));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(loop).execute(task.capture());
        task.getValue().run();

        ArgumentCaptor<WebSocketFrame> written = ArgumentCaptor.forClass(WebSocketFrame.class);
        verify(channel).writeAndFlush(written.capture());
        assertEquals("off-loop", ((TextWebSocketFrame) written.getValue()).text());
        written.getValue().release();
    }

    @Test
    void sendMessage_asyncTaskThrows_notifiesTransportError() throws Exception {
        // 移交到 eventLoop 后写失败的异常不会回到调用线程，必须走 handleTransportError，
        // 否则它变成未捕获异常被 EventLoop 吞掉，业务永远不知道消息没发出去。
        Channel channel = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        when(channel.eventLoop()).thenReturn(loop);
        when(channel.isActive()).thenReturn(true);
        when(channel.isWritable()).thenReturn(true);
        when(loop.inEventLoop()).thenReturn(false);
        RuntimeException boom = new RuntimeException("write boom");
        when(channel.writeAndFlush(any(WebSocketFrame.class))).thenThrow(boom);
        WebSocketHandler handler = mock(WebSocketHandler.class);

        NettyWebSocketSession session = new NettyWebSocketSession(channel, uri, null, null, null, null, null, handler);
        session.sendMessage(new TextMessage("x"));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(loop).execute(task.capture());
        assertDoesNotThrow(task.getValue()::run, "异步任务的异常必须被兜住，不能逃逸到 EventLoop");

        verify(handler).handleTransportError(same(session), same(boom));
    }

    @Test
    void sendMessage_asyncTaskThrowsWithoutHandler_doesNotPropagate() throws Exception {
        // handler 为 null 时只能记日志：该路径同样不能抛
        Channel channel = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        when(channel.eventLoop()).thenReturn(loop);
        when(channel.isActive()).thenReturn(true);
        when(channel.isWritable()).thenReturn(true);
        when(loop.inEventLoop()).thenReturn(false);
        when(channel.writeAndFlush(any(WebSocketFrame.class))).thenThrow(new RuntimeException("write boom"));

        NettyWebSocketSession session = new NettyWebSocketSession(channel, uri, null, null, null, null, null, null);
        session.sendMessage(new TextMessage("x"));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(loop).execute(task.capture());
        assertDoesNotThrow(task.getValue()::run);
    }

    @Test
    void sendMessage_writeFailureWithoutHandler_ignored() throws Exception {
        // 会话可能没有关联 handler（构造器 @Nullable）：写失败时只能静默丢弃，不能 NPE
        Channel channel = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        ChannelFuture future = mock(ChannelFuture.class);
        when(channel.eventLoop()).thenReturn(loop);
        when(channel.isActive()).thenReturn(true);
        when(channel.isWritable()).thenReturn(true);
        when(loop.inEventLoop()).thenReturn(true);
        when(channel.writeAndFlush(any(WebSocketFrame.class))).thenReturn(future);
        when(future.isSuccess()).thenReturn(false);

        NettyWebSocketSession session = new NettyWebSocketSession(channel, uri, null, null, null, null, null, null);
        assertDoesNotThrow(() -> session.sendMessage(new TextMessage("x")));

        ArgumentCaptor<WebSocketFrame> written = ArgumentCaptor.forClass(WebSocketFrame.class);
        verify(channel).writeAndFlush(written.capture());
        written.getValue().release();
    }

    @Test
    void sendMessage_transportErrorHandlerThrows_doesNotPropagate() throws Exception {
        // 业务方的 handleTransportError 自己抛异常时，只能记日志：
        // 让它在 EventLoop 任务里逃逸会变成未捕获异常，且后续清理逻辑被跳过
        Channel channel = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        when(channel.eventLoop()).thenReturn(loop);
        when(channel.isActive()).thenReturn(true);
        when(channel.isWritable()).thenReturn(true);
        when(loop.inEventLoop()).thenReturn(false);
        when(channel.writeAndFlush(any(WebSocketFrame.class))).thenThrow(new RuntimeException("write boom"));
        WebSocketHandler handler = mock(WebSocketHandler.class);
        doThrow(new RuntimeException("handler boom")).when(handler).handleTransportError(any(), any());

        NettyWebSocketSession session = new NettyWebSocketSession(channel, uri, null, null, null, null, null, handler);
        session.sendMessage(new TextMessage("x"));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(loop).execute(task.capture());
        assertDoesNotThrow(task.getValue()::run, "错误回调自身抛异常不得逃逸到 EventLoop");
        verify(handler).handleTransportError(same(session), any());
    }

    @Test
    void sendMessage_writeFailure_notifiesTransportError() throws Exception {
        // 写完才知道失败（异步 write）：由 future listener 兜住，否则消息静默丢失
        Channel channel = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        ChannelFuture future = mock(ChannelFuture.class);
        when(channel.eventLoop()).thenReturn(loop);
        when(channel.isActive()).thenReturn(true);
        when(channel.isWritable()).thenReturn(true);
        when(loop.inEventLoop()).thenReturn(true);
        when(channel.writeAndFlush(any(WebSocketFrame.class))).thenReturn(future);
        java.io.IOException cause = new java.io.IOException("write failed");
        when(future.isSuccess()).thenReturn(false);
        when(future.cause()).thenReturn(cause);
        WebSocketHandler handler = mock(WebSocketHandler.class);

        NettyWebSocketSession session = new NettyWebSocketSession(channel, uri, null, null, null, null, null, handler);
        session.sendMessage(new TextMessage("x"));

        ArgumentCaptor<WebSocketFrame> written = ArgumentCaptor.forClass(WebSocketFrame.class);
        verify(channel).writeAndFlush(written.capture());
        written.getValue().release();

        ArgumentCaptor<ChannelFutureListener> listener = ArgumentCaptor.forClass(ChannelFutureListener.class);
        verify(future).addListener(listener.capture());
        listener.getValue().operationComplete(future);

        verify(handler).handleTransportError(same(session), same(cause));
    }
}

/** 非 Text/Binary/Ping/Pong 的未知消息类型，用于验证 {@code toFrame} 的兜底返回 null。 */
final class OpaqueMessage implements WebSocketMessage<byte[]> {

    @Override
    public byte[] getPayload() {
        return new byte[0];
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
