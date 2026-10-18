package io.springperf.web.websocket.jsr;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.springperf.web.websocket.server.NettyWebSocketSession;
import io.springperf.web.websocket.server.SpringHeadersAdapter;
import jakarta.websocket.EncodeException;
import jakarta.websocket.RemoteEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
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

    @Test
    void getSendStream_unsupported() {
        assertThrows(UnsupportedOperationException.class, remote::getSendStream);
        assertThrows(UnsupportedOperationException.class, remote::getSendWriter);
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
                java.net.URI.create("ws://localhost/ws"),
                new SpringHeadersAdapter(new io.netty.handler.codec.http.DefaultHttpHeaders(false)), null,
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
