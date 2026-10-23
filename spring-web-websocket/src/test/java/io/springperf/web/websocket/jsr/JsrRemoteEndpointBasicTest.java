package io.springperf.web.websocket.jsr;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.springperf.web.websocket.server.NettyWebSocketSession;
import org.springframework.http.HttpHeaders;
import jakarta.websocket.EncodeException;
import jakarta.websocket.RemoteEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class JsrRemoteEndpointBasicTest {

    private WebSocketSession springSession = mock(WebSocketSession.class);
    private JsrCodecRegistry codecRegistry = new JsrCodecRegistry(
            new JsrEndpointConfigAdapter(new JsrEndpointMetadata(CodecEndpoint.class)));
    private JsrWebSocketSession session = new JsrWebSocketSession(springSession, new JsrWebSocketContainer(),
            codecRegistry, java.util.Collections.emptyMap());
    private JsrRemoteEndpointBasic remote = new JsrRemoteEndpointBasic(session, codecRegistry);

    @jakarta.websocket.server.ServerEndpoint(value = "/e", encoders = { TextEncoder.class })
    static class CodecEndpoint {
    }

    public static class TextEncoder implements jakarta.websocket.Encoder.Text<String> {
        @Override
        public String encode(String o) {
            return "encoded:" + o;
        }

        @Override
        public void init(jakarta.websocket.EndpointConfig config) {
        }

        @Override
        public void destroy() {
        }
    }

    @Test
    void sendText_sendsTextMessage() throws IOException {
        remote.sendText("hello");
        verify(springSession).sendMessage(new TextMessage("hello"));
    }

    @Test
    void sendBinary_sendsBinaryMessage() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8));
        remote.sendBinary(buf);
        verify(springSession).sendMessage(new BinaryMessage(buf));
    }

    /**
     * {@code AbstractWebSocketMessage.equals} 只比较 payload、忽略 isLast，因此下面必须用 ArgumentCaptor 显式断言该标志，否则"丢弃 isLast"的实现也能让
     * verify 通过。
     */
    @Test
    void sendText_nonLastFragment_propagatesIsLastFalse() throws IOException {
        remote.sendText("part", false);

        org.mockito.ArgumentCaptor<WebSocketMessage<?>> captor = org.mockito.ArgumentCaptor
                .forClass(WebSocketMessage.class);
        verify(springSession).sendMessage(captor.capture());
        assertFalse(captor.getValue().isLast(), "last=false 的分片必须透传，否则客户端会把分片当完整消息");
    }

    @Test
    void sendText_lastFragment_propagatesIsLastTrue() throws IOException {
        remote.sendText("part", true);

        org.mockito.ArgumentCaptor<WebSocketMessage<?>> captor = org.mockito.ArgumentCaptor
                .forClass(WebSocketMessage.class);
        verify(springSession).sendMessage(captor.capture());
        assertTrue(captor.getValue().isLast());
    }

    @Test
    void sendBinary_nonLastFragment_propagatesIsLastFalse() throws IOException {
        remote.sendBinary(ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8)), false);

        org.mockito.ArgumentCaptor<WebSocketMessage<?>> captor = org.mockito.ArgumentCaptor
                .forClass(WebSocketMessage.class);
        verify(springSession).sendMessage(captor.capture());
        assertFalse(captor.getValue().isLast(), "二进制分片的 last 标志同样必须透传");
    }

    @Test
    void sendText_partialAndLast_sendsFullText() throws IOException {
        remote.sendText("part", true);
        verify(springSession).sendMessage(new TextMessage("part"));
    }

    @Test
    void sendBinary_partialAndLast_sendsFullBinary() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8));
        remote.sendBinary(buf, false);
        verify(springSession).sendMessage(new BinaryMessage(buf));
    }

    /**
     * 消息边界是 flush/close 而不是每次 write：write 阶段不得落网，否则一条业务消息会被拆成多条 WebSocket 消息。
     */
    @Test
    void getSendStream_buffersUntilFlush_sendsSingleBinaryMessage() throws IOException {
        OutputStream out = remote.getSendStream();
        out.write(new byte[] { 1, 2 });
        out.write(3);
        verify(springSession, times(0)).sendMessage(any());

        out.flush();

        org.mockito.ArgumentCaptor<WebSocketMessage<?>> captor = org.mockito.ArgumentCaptor
                .forClass(WebSocketMessage.class);
        verify(springSession).sendMessage(captor.capture());
        assertTrue(captor.getValue() instanceof BinaryMessage, "getSendStream 必须走二进制消息");
        assertArrayEquals(new byte[] { 1, 2, 3 }, payloadBytes(((BinaryMessage) captor.getValue()).getPayload()));
    }

    /** 缓冲已空时再次 flush 不得发出空消息——空 WebSocket 消息对端无法区分于业务空内容。 */
    @Test
    void getSendStream_flushTwice_secondFlushSendsNothing() throws IOException {
        OutputStream out = remote.getSendStream();
        out.write(1);
        out.flush();
        out.flush();

        verify(springSession, times(1)).sendMessage(any());
    }

    @Test
    void getSendStream_closeSendsBufferAndIsIdempotent() throws IOException {
        OutputStream out = remote.getSendStream();
        out.write(new byte[] { 9 });
        out.close();
        out.close();

        verify(springSession, times(1)).sendMessage(argThat(msg -> msg instanceof BinaryMessage));
        assertThrows(IOException.class, () -> out.write(1), "关闭后写入必须抛 IOException");
    }

    @Test
    void getSendWriter_flush_sendsTextMessage() throws IOException {
        Writer writer = remote.getSendWriter();
        writer.write("hello");
        verify(springSession, times(0)).sendMessage(any());

        writer.flush();

        org.mockito.ArgumentCaptor<WebSocketMessage<?>> captor = org.mockito.ArgumentCaptor
                .forClass(WebSocketMessage.class);
        verify(springSession).sendMessage(captor.capture());
        assertTrue(captor.getValue() instanceof TextMessage, "getSendWriter 必须走文本消息");
        assertEquals("hello", ((TextMessage) captor.getValue()).getPayload());
    }

    /**
     * 代理对（emoji）被 write 边界切开时必须由编码器补齐：否则会把半个字符编成 {@code ?}，对端收到乱码。
     */
    @Test
    void getSendWriter_surrogateSplitAcrossWrites_stillEncodesWholeChar() throws IOException {
        String emoji = "\uD83D\uDE00";
        Writer writer = remote.getSendWriter();
        writer.write(emoji.toCharArray(), 0, 1);
        writer.write(emoji.toCharArray(), 1, 1);
        writer.close();

        org.mockito.ArgumentCaptor<WebSocketMessage<?>> captor = org.mockito.ArgumentCaptor
                .forClass(WebSocketMessage.class);
        verify(springSession).sendMessage(captor.capture());
        assertEquals(emoji, ((TextMessage) captor.getValue()).getPayload());
    }

    @Test
    void getSendWriter_closeIsIdempotentAndRejectsWriteAfterClose() throws IOException {
        Writer writer = remote.getSendWriter();
        writer.write("x");
        writer.close();
        writer.close();

        verify(springSession, times(1)).sendMessage(argThat(msg -> msg instanceof TextMessage));
        assertThrows(IOException.class, () -> writer.write("y"), "关闭后写入必须抛 IOException");
    }

    /** 端到端：一次 flush 只落一帧 final 的二进制帧——帧边界即消息边界，写侧不能被拆成多帧。 */
    @Test
    void getSendStream_endToEnd_singleFinalFrame() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        RemoteEndpoint.Basic endpoint = newBatchedRemote(channel);

        OutputStream out = endpoint.getSendStream();
        out.write(new byte[] { 7, 8 });
        out.flush();

        BinaryWebSocketFrame frame = channel.readOutbound();
        assertNotNull(frame, "flush 后必须有帧落网");
        assertTrue(frame.isFinalFragment(), "整条消息必须一次发出（final 帧）");
        byte[] actual = new byte[frame.content().readableBytes()];
        frame.content().getBytes(frame.content().readerIndex(), actual);
        assertArrayEquals(new byte[] { 7, 8 }, actual);
        assertNull(channel.readOutbound(), "一条流式消息只能产生一帧");
        frame.release();
        channel.finishAndReleaseAll();
    }

    private static byte[] payloadBytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    @Test
    void sendObject_textEncoder_sendsText() throws IOException, EncodeException {
        remote.sendObject("world");
        verify(springSession).sendMessage(new TextMessage("encoded:world"));
    }

    @jakarta.websocket.server.ServerEndpoint(value = "/bin", encoders = { BinEncoder.class })
    static class BinaryCodecEndpoint {
    }

    public static class BinEncoder implements jakarta.websocket.Encoder.Binary<byte[]> {
        @Override
        public ByteBuffer encode(byte[] o) {
            return ByteBuffer.wrap(o);
        }

        @Override
        public void init(jakarta.websocket.EndpointConfig config) {
        }

        @Override
        public void destroy() {
        }
    }

    @Test
    void sendObject_binaryEncoder_sendsBinaryMessage() throws Exception {
        // 编码器声明为 Encoder.Binary 时必须走 BinaryMessage：误走 TextMessage 会让对端按文本解析二进制载荷
        JsrCodecRegistry binRegistry = new JsrCodecRegistry(
                new JsrEndpointConfigAdapter(new JsrEndpointMetadata(BinaryCodecEndpoint.class)));
        JsrWebSocketSession binSession = new JsrWebSocketSession(springSession, new JsrWebSocketContainer(),
                binRegistry, java.util.Collections.emptyMap());

        new JsrRemoteEndpointBasic(binSession, binRegistry).sendObject(new byte[] { 1, 2, 3 });

        verify(springSession).sendMessage(argThat(msg -> msg instanceof BinaryMessage));
    }

    @Test
    void setBatchingAllowed_togglesFlag() throws IOException {
        assertFalse(remote.getBatchingAllowed());
        remote.setBatchingAllowed(true);
        assertTrue(remote.getBatchingAllowed());
        remote.setBatchingAllowed(false);
        assertFalse(remote.getBatchingAllowed());
    }

    /**
     * 规范（jakarta.websocket RemoteEndpoint#sendBinary）：发送成功后 {@code ByteBuffer.position()} 必须等于 limit。
     * <p>
     * 底层用 duplicate 读取不会替你推进，Basic 必须补上；依赖该副作用的代码（循环发送、池化回收）行为才与其他实现一致。
     * </p>
     */
    @Test
    void sendBinary_advancesPositionToLimit() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        RemoteEndpoint.Basic basic = newBatchedRemote(channel);
        ByteBuffer payload = ByteBuffer.wrap(new byte[] { 1, 2, 3 });

        basic.sendBinary(payload);

        assertEquals(payload.limit(), payload.position(), "发送成功后 position 应推进到 limit");
        BinaryWebSocketFrame frame = channel.readOutbound();
        assertEquals(3, frame.content().readableBytes(), "推进 position 不得影响已发出的数据");
        frame.release();
        channel.finishAndReleaseAll();
    }

    /** 底层无法保证「返回即已发送完成」时不得推进 position：否则底层实现会读到空数据。 */
    @Test
    void sendBinary_nonNettySession_doesNotAdvancePosition() throws IOException {
        ByteBuffer payload = ByteBuffer.wrap(new byte[] { 1, 2, 3 });

        remote.sendBinary(payload);

        assertEquals(0, payload.position(), "非 Netty 后端不保证完成，推进 position 会丢数据");
        verify(springSession).sendMessage(any());
    }

    /** 批处理开启后消息必须停在 outbound buffer，只有 flushBatch 才落网——否则「批处理」等价于逐条发送。 */
    @Test
    void batching_defersFramesUntilFlushBatch() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        RemoteEndpoint.Basic batched = newBatchedRemote(channel);

        batched.setBatchingAllowed(true);
        batched.sendText("a");
        batched.sendText("b");
        channel.runPendingTasks();
        assertNull(channel.readOutbound(), "开启批处理时不得逐条落网");

        batched.flushBatch();
        TextWebSocketFrame first = channel.readOutbound();
        TextWebSocketFrame second = channel.readOutbound();
        assertEquals("a", first.text());
        assertEquals("b", second.text());
        assertNull(channel.readOutbound(), "flushBatch 应一次刷出全部暂存帧");
        first.release();
        second.release();
        channel.finishAndReleaseAll();
    }

    /** JSR-356 语义：把 batching 由 true 改为 false 时，必须先把暂存帧刷出。 */
    @Test
    void setBatchingAllowed_falseFlushesPendingBatch() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        RemoteEndpoint.Basic batched = newBatchedRemote(channel);

        batched.setBatchingAllowed(true);
        batched.sendText("a");
        assertNull(channel.readOutbound());

        batched.setBatchingAllowed(false);
        TextWebSocketFrame frame = channel.readOutbound();
        assertEquals("a", frame.text());
        frame.release();
        channel.finishAndReleaseAll();
    }

    /** 控制帧不受批处理影响：心跳滞留会被对端当作空闲超时。 */
    @Test
    void batching_pingIsFlushedImmediately() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        RemoteEndpoint.Basic batched = newBatchedRemote(channel);

        batched.setBatchingAllowed(true);
        batched.sendText("a");
        batched.sendPing(ByteBuffer.wrap(new byte[] { 1 }));
        channel.runPendingTasks();

        assertTrue(channel.readOutbound() instanceof TextWebSocketFrame);
        assertTrue(channel.readOutbound() instanceof PingWebSocketFrame);
        channel.finishAndReleaseAll();
    }

    @Test
    void sendPing_payloadReachesFrame() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        RemoteEndpoint.Basic batched = newBatchedRemote(channel);

        batched.sendPing(ByteBuffer.wrap(new byte[] { 1, 2 }));

        PingWebSocketFrame frame = channel.readOutbound();
        assertNotNull(frame, "Ping 帧必须立即落网（心跳不受批处理影响）");
        byte[] actual = new byte[frame.content().readableBytes()];
        frame.content().getBytes(frame.content().readerIndex(), actual);
        assertArrayEquals(new byte[] { 1, 2 }, actual, "JSR 的 sendPing 应用数据不得被丢弃");
        frame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void sendPingPong_oversizedPayload_rejected() throws IOException {
        // RFC 6455 §5.5 + JSR-356：控制帧载荷上限 125 字节，超限是协议违规，必须在发送前拒绝
        assertThrows(IllegalArgumentException.class, () -> remote.sendPing(ByteBuffer.wrap(new byte[126])));
        assertThrows(IllegalArgumentException.class, () -> remote.sendPong(ByteBuffer.wrap(new byte[126])));
    }

    /** 底层不是 Netty 实现时没有暂存能力：必须退化为逐条立即发送，而不是静默丢弃消息。 */
    @Test
    void batching_nonNettySession_fallsBackToImmediateSend() throws IOException {
        remote.setBatchingAllowed(true);
        remote.sendText("hello");
        remote.flushBatch();

        verify(springSession).sendMessage(new TextMessage("hello"));
    }

    private RemoteEndpoint.Basic newBatchedRemote(EmbeddedChannel channel) {
        NettyWebSocketSession nettySession = new NettyWebSocketSession(channel,
                java.net.URI.create("ws://localhost/ws"), new HttpHeaders(), null,
                new java.net.InetSocketAddress("127.0.0.1", 8080), null, null, null);
        JsrWebSocketSession jsrSession = new JsrWebSocketSession(nettySession, new JsrWebSocketContainer(),
                codecRegistry, java.util.Collections.emptyMap());
        return jsrSession.getBasicRemote();
    }

    @Test
    void sendPing_sendsPingMessage() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap("p".getBytes(StandardCharsets.UTF_8));
        remote.sendPing(buf);
        verify(springSession).sendMessage(new PingMessage(buf));
    }

    @Test
    void sendPong_sendsPongMessage() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap("o".getBytes(StandardCharsets.UTF_8));
        remote.sendPong(buf);
        verify(springSession).sendMessage(new PongMessage(buf));
    }
}
