package io.springperf.web.websocket.jsr;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.springperf.web.websocket.server.NettyWebSocketSession;
import org.springframework.http.HttpHeaders;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JsrRemoteEndpointAsyncTest {

    private WebSocketSession springSession = mock(WebSocketSession.class);
    private JsrCodecRegistry codecRegistry = new JsrCodecRegistry(
            new JsrEndpointConfigAdapter(new JsrEndpointMetadata(CodecEndpoint.class)));
    private JsrWebSocketSession session = new JsrWebSocketSession(springSession, new JsrWebSocketContainer(),
            codecRegistry, Collections.emptyMap());
    private JsrRemoteEndpointAsync remote = new JsrRemoteEndpointAsync(session, codecRegistry);

    @javax.websocket.server.ServerEndpoint(value = "/e", encoders = {TextEncoder.class})
    static class CodecEndpoint {}

    public static class TextEncoder implements javax.websocket.Encoder.Text<String> {
        @Override public String encode(String o) { return "encoded:" + o; }
        @Override public void init(javax.websocket.EndpointConfig config) {}
        @Override public void destroy() {}
    }

    @Test
    void sendTimeout_getSet() {
        assertEquals(-1L, remote.getSendTimeout());
        remote.setSendTimeout(3000);
        assertEquals(3000L, remote.getSendTimeout());
    }

    @Test
    void sendText_handler_success() throws IOException {
        SendHandler handler = mock(SendHandler.class);
        remote.sendText("hello", handler);
        verify(springSession).sendMessage(new TextMessage("hello"));
        verify(handler, times(1)).onResult(argThat(SendResult::isOK));
        verifyNoMoreInteractions(handler);
    }

    @Test
    void sendText_handler_failure() throws IOException {
        doThrow(new IOException("boom")).when(springSession).sendMessage(any(TextMessage.class));
        SendHandler handler = mock(SendHandler.class);
        remote.sendText("hello", handler);
        verify(handler).onResult(argThat(result -> !result.isOK()));
    }

    @Test
    void sendText_future_success() throws Exception {
        Future<Void> future = remote.sendText("hello");
        assertTrue(future.isDone());
        future.get();
        verify(springSession).sendMessage(new TextMessage("hello"));
    }

    @Test
    void sendText_future_failure() throws IOException {
        doThrow(new IOException("boom")).when(springSession).sendMessage(any(TextMessage.class));
        Future<Void> future = remote.sendText("hello");
        java.util.concurrent.ExecutionException ex = assertThrows(java.util.concurrent.ExecutionException.class,
                future::get);
        assertTrue(ex.getCause() instanceof IOException, "原始 IOException 必须保留为 cause");
        assertEquals("boom", ex.getCause().getMessage(), "cause 必须来自实际写失败，而不是被换掉的异常");
    }

    @Test
    void sendBinary_future_success() throws Exception {
        ByteBuffer buf = ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8));
        Future<Void> future = remote.sendBinary(buf);
        future.get();
        verify(springSession).sendMessage(new BinaryMessage(buf));
    }

    @Test
    void sendBinary_handler_failure() throws IOException {
        doThrow(new IOException("boom")).when(springSession).sendMessage(any(BinaryMessage.class));
        SendHandler handler = mock(SendHandler.class);
        remote.sendBinary(ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8)), handler);
        verify(handler).onResult(argThat(result -> !result.isOK()));
    }

    @Test
    void sendObject_future_success() throws Exception {
        Future<Void> future = remote.sendObject("world");
        future.get();
        verify(springSession).sendMessage(new TextMessage("encoded:world"));
    }

    @Test
    void sendObject_handler_success() throws IOException {
        SendHandler handler = mock(SendHandler.class);
        remote.sendObject("world", handler);
        verify(springSession).sendMessage(new TextMessage("encoded:world"));
        verify(handler, times(1)).onResult(argThat(SendResult::isOK));
        verifyNoMoreInteractions(handler);
    }

    @Test
    void setBatchingAllowed_togglesFlag() throws IOException {
        assertFalse(remote.getBatchingAllowed());
        remote.setBatchingAllowed(true);
        assertTrue(remote.getBatchingAllowed());
        remote.setBatchingAllowed(false);
        assertFalse(remote.getBatchingAllowed());
    }

    /** Async 与 Basic 共用底层 outbound buffer：批处理开启时 sendXxx 也不得逐条落网。 */
    @Test
    void batching_defersFramesUntilFlushBatch() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession nettySession = new NettyWebSocketSession(channel,
                java.net.URI.create("ws://localhost/ws"), new HttpHeaders(), null,
                new java.net.InetSocketAddress("127.0.0.1", 8080), null, null, null);
        JsrWebSocketSession jsrSession = new JsrWebSocketSession(nettySession, new JsrWebSocketContainer(),
                codecRegistry, Collections.emptyMap());

        javax.websocket.RemoteEndpoint.Async batched = jsrSession.getAsyncRemote();
        batched.setBatchingAllowed(true);
        java.util.concurrent.Future<Void> future = batched.sendText("a");
        // future 绑定的是「帧写入完成」而非「入队即完成」：EmbeddedChannel 需驱动一次任务队列
        channel.runPendingTasks();
        assertTrue(future.isDone(), "帧写入 outbound buffer 后 future 即完成（flush 由调用方控制）");
        assertNull(channel.readOutbound(), "开启批处理时不得逐条落网");

        batched.flushBatch();
        TextWebSocketFrame frame = channel.readOutbound();
        assertEquals("a", frame.text());
        frame.release();
        channel.finishAndReleaseAll();
    }

    /** Async 同样受 position 契约约束：完成时把 position 推进到 limit，调用方随即可复用 buffer。 */
    @Test
    void sendBinary_completionAdvancesPosition() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession nettySession = new NettyWebSocketSession(channel,
                java.net.URI.create("ws://localhost/ws"), new HttpHeaders(), null,
                new java.net.InetSocketAddress("127.0.0.1", 8080), null, null, null);
        JsrWebSocketSession jsrSession = new JsrWebSocketSession(nettySession, new JsrWebSocketContainer(),
                codecRegistry, Collections.emptyMap());
        ByteBuffer payload = ByteBuffer.wrap(new byte[] { 7, 8 });

        java.util.concurrent.Future<Void> future = jsrSession.getAsyncRemote().sendBinary(payload);
        channel.runPendingTasks();
        future.get(2, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(payload.limit(), payload.position());
        BinaryWebSocketFrame frame = channel.readOutbound();
        assertEquals(2, frame.content().readableBytes(), "推进 position 不得影响已发出的数据");
        frame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void sendPingOversizedPayload_rejected() throws IOException {
        // 与 Basic 同一约束：控制帧载荷 125 字节上限，超限必须在发送前抛 IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> remote.sendPing(ByteBuffer.wrap(new byte[126])));
        assertThrows(IllegalArgumentException.class, () -> remote.sendPong(ByteBuffer.wrap(new byte[126])));
        verify(springSession, never()).sendMessage(any());
    }

    @Test
    void batching_nonNettySession_fallsBackToImmediateSend() throws Exception {
        remote.setBatchingAllowed(true);
        remote.sendText("hello").get();
        remote.flushBatch();

        verify(springSession).sendMessage(new TextMessage("hello"));
    }

    @Test
    void sendPingAndPong_sendsMessages() throws IOException {
        ByteBuffer p = ByteBuffer.wrap("p".getBytes(StandardCharsets.UTF_8));
        ByteBuffer o = ByteBuffer.wrap("o".getBytes(StandardCharsets.UTF_8));
        remote.sendPing(p);
        remote.sendPong(o);
        verify(springSession).sendMessage(new PingMessage(p));
        verify(springSession).sendMessage(new PongMessage(o));
    }

    @Test
    void sendBinary_future_failure() throws IOException {
        doThrow(new IOException("boom")).when(springSession).sendMessage(any(BinaryMessage.class));
        Future<Void> future = remote.sendBinary(ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8)));
        assertThrows(java.util.concurrent.ExecutionException.class, future::get);
    }

    @javax.websocket.server.ServerEndpoint(value = "/bin", encoders = {BinEncoder.class})
    static class BinaryCodecEndpoint {
    }

    public static class BinEncoder implements javax.websocket.Encoder.Binary<byte[]> {
        @Override
        public ByteBuffer encode(byte[] o) {
            return ByteBuffer.wrap(o);
        }

        @Override
        public void init(javax.websocket.EndpointConfig config) {
        }

        @Override
        public void destroy() {
        }
    }

    private JsrRemoteEndpointAsync binaryRemote() {
        JsrCodecRegistry binaryRegistry = new JsrCodecRegistry(
                new JsrEndpointConfigAdapter(new JsrEndpointMetadata(BinaryCodecEndpoint.class)));
        JsrWebSocketSession binarySession = new JsrWebSocketSession(springSession,
                new JsrWebSocketContainer(), binaryRegistry, Collections.emptyMap());
        return new JsrRemoteEndpointAsync(binarySession, binaryRegistry);
    }

    @Test
    void sendObject_binaryEncoder_sendsBinaryMessage() throws Exception {
        JsrRemoteEndpointAsync binRemote = binaryRemote();
        Future<Void> future = binRemote.sendObject(new byte[]{1, 2, 3});
        future.get();
        verify(springSession).sendMessage(argThat(msg -> msg instanceof BinaryMessage));
    }

    @Test
    void sendObject_future_failure_encodesCompletesExceptionally() {
        // 无匹配 Encoder → EncodeException → future 异常完成
        Future<Void> future = remote.sendObject(new Object());
        assertThrows(java.util.concurrent.ExecutionException.class, future::get);
    }

    @Test
    void sendObject_handler_failure_reportsError() throws IOException {
        SendHandler handler = mock(SendHandler.class);
        remote.sendObject(new Object(), handler);
        verify(handler).onResult(argThat(result -> !result.isOK()));
    }

    @Test
    void sendBinary_handler_success() throws IOException {
        // handler 重载此前只有失败用例：成功路径不打 onResult 会让调用方永远等不到完成回调
        SendHandler handler = mock(SendHandler.class);
        ByteBuffer buf = ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8));

        remote.sendBinary(buf, handler);

        verify(springSession).sendMessage(new BinaryMessage(buf));
        verify(handler).onResult(argThat(SendResult::isOK));
    }

    @Test
    void sendObject_handler_binaryEncoder_sendsBinaryMessage() throws IOException {
        JsrRemoteEndpointAsync binRemote = binaryRemote();
        SendHandler handler = mock(SendHandler.class);

        binRemote.sendObject(new byte[] { 1, 2, 3 }, handler);

        verify(springSession).sendMessage(argThat(msg -> msg instanceof BinaryMessage));
        verify(handler).onResult(argThat(SendResult::isOK));
    }

    /* ==================== 真实 Netty 异步通道（非 mock 同步降级） ==================== */

    private javax.websocket.RemoteEndpoint.Async realNettyAsync(EmbeddedChannel channel) {
        NettyWebSocketSession nettySession = new NettyWebSocketSession(channel,
                java.net.URI.create("ws://localhost/ws"), new HttpHeaders(), null,
                new java.net.InetSocketAddress("127.0.0.1", 8080), null, null, null);
        JsrWebSocketSession jsrSession = new JsrWebSocketSession(nettySession, new JsrWebSocketContainer(),
                codecRegistry, Collections.emptyMap());
        return jsrSession.getAsyncRemote();
    }

    @Test
    void realNettySession_sendText_futureCompletesAfterFrameIsWritten() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        javax.websocket.RemoteEndpoint.Async async = realNettyAsync(channel);

        Future<Void> future = async.sendText("netty");
        channel.runPendingTasks();

        assertTrue(future.isDone(), "真实通道上 future 必须在帧写入后完成");
        assertNull(future.get(2, java.util.concurrent.TimeUnit.SECONDS),
                "正常完成应返回 null；异常完成会抛 ExecutionException，不会走到这里");
        TextWebSocketFrame frame = channel.readOutbound();
        assertNotNull(frame, "必须真的向 Netty 通道写出帧");
        assertEquals("netty", frame.text());
        frame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void realNettySession_sendText_handlerGetsSuccessOnlyAfterWrite() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        javax.websocket.RemoteEndpoint.Async async = realNettyAsync(channel);
        SendHandler handler = mock(SendHandler.class);

        async.sendText("netty", handler);
        channel.runPendingTasks();

        verify(handler, times(1)).onResult(argThat(SendResult::isOK));
        verifyNoMoreInteractions(handler);
        TextWebSocketFrame frame = channel.readOutbound();
        assertNotNull(frame, "回调成功的同时帧必须真的写出");
        assertEquals("netty", frame.text());
        frame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void realNettySession_closedSession_futureFailsWithIOException() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession nettySession = new NettyWebSocketSession(channel,
                java.net.URI.create("ws://localhost/ws"), new HttpHeaders(), null,
                new java.net.InetSocketAddress("127.0.0.1", 8080), null, null, null);
        JsrWebSocketSession jsrSession = new JsrWebSocketSession(nettySession, new JsrWebSocketContainer(),
                codecRegistry, Collections.emptyMap());
        nettySession.setOpen(false);

        Future<Void> future = jsrSession.getAsyncRemote().sendText("x");

        java.util.concurrent.ExecutionException ex = assertThrows(java.util.concurrent.ExecutionException.class,
                future::get);
        assertInstanceOf(IOException.class, ex.getCause(), "关闭会话必须让 future 异常完成为 IOException");
        assertTrue(ex.getCause().getMessage().contains("is closed"), "失败原因必须说明会话已关闭");
        assertNull(channel.readOutbound(), "已关闭会话不得写出帧");
        channel.finishAndReleaseAll();
    }
}
