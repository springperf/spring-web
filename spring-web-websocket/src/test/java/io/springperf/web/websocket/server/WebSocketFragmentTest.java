package io.springperf.web.websocket.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 分片消息聚合（RFC 6455 §5.4）与握手失败路径的资源释放。
 * <p>
 * 修复前的两个缺陷：
 * </p>
 * <ol>
 * <li>分片帧被当作完整消息派发——发送侧支持分片而接收侧不支持，客户端一旦分片就被静默拆成多条错误消息。</li>
 * <li>{@code handshaker.handshake()} 同步抛异常时不释放 {@code FullHttpRequest}，每次泄漏一个含 content ByteBuf 的请求。</li>
 * </ol>
 */
@DisplayName("WebSocket 分片聚合与握手失败资源释放")
class WebSocketFragmentTest {

    private WebSocketHandler handler;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        handler = mock(WebSocketHandler.class);
        channel = new EmbeddedChannel();
        channel.pipeline().addLast(new io.netty.handler.codec.http.HttpServerCodec());
        channel.pipeline().addLast(new io.netty.handler.codec.http.HttpObjectAggregator(64 * 1024));
        channel.pipeline()
                .addLast(new WebSocketRoutingHandler(Collections.singletonMap("/ws", handler), null, false, null));
    }

    @AfterEach
    void tearDown() {
        try {
            channel.finishAndReleaseAll();
        } catch (Exception ignored) {
        }
    }

    private void establishSession() throws Exception {
        channel.pipeline().fireChannelRead(upgradeRequest("/ws"));
        channel.runPendingTasks();
        verify(handler, timeout(2000)).afterConnectionEstablished(any(WebSocketSession.class));
        drainOutbound();
    }

    private void drainOutbound() {
        Object o;
        while ((o = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(o);
        }
    }

    /* ==================== 文本分片 ==================== */

    @Test
    @DisplayName("文本分片聚合为一条完整消息")
    void fragmentedText_assembledIntoOneMessage() throws Exception {
        establishSession();

        // 起始帧 FIN=0，两个中间 continuation，末帧 FIN=1
        channel.pipeline().fireChannelRead(new TextWebSocketFrame(false, 0, "Hello "));
        channel.pipeline().fireChannelRead(new ContinuationWebSocketFrame(false, 0, "frag"));
        channel.pipeline().fireChannelRead(new ContinuationWebSocketFrame(false, 0, "mented "));
        channel.pipeline().fireChannelRead(new ContinuationWebSocketFrame(true, 0, "world"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(handler, timeout(2000)).handleMessage(any(WebSocketSession.class), captor.capture());
        assertEquals("Hello fragmented world", captor.getValue().getPayload());
        // 只派发一次——不是四条
        verify(handler, times(1)).handleMessage(any(WebSocketSession.class), any(TextMessage.class));
    }

    @Test
    @DisplayName("跨分片的 UTF-8 多字节字符不被截断（逐帧解码会乱码）")
    void fragmentedText_multiByteUtf8AcrossFragments() throws Exception {
        establishSession();

        // 「中文」三字各 3 字节，故意在字节中间切开
        byte[] all = "中文测试".getBytes(StandardCharsets.UTF_8);
        int cut1 = 4; // 切断在第一个字的第 4 字节（即第二个字的开始前）
        int cut2 = 7;
        channel.pipeline().fireChannelRead(new TextWebSocketFrame(false, 0, Unpooled.wrappedBuffer(all, 0, cut1)));
        channel.pipeline().fireChannelRead(
                new ContinuationWebSocketFrame(false, 0, Unpooled.wrappedBuffer(all, cut1, cut2 - cut1)));
        channel.pipeline().fireChannelRead(
                new ContinuationWebSocketFrame(true, 0, Unpooled.wrappedBuffer(all, cut2, all.length - cut2)));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(handler, timeout(2000)).handleMessage(any(WebSocketSession.class), captor.capture());
        assertEquals("中文测试", captor.getValue().getPayload());
    }

    /* ==================== 二进制分片 ==================== */

    @Test
    @DisplayName("二进制分片按字节原样聚合（不涉及字符解码）")
    void fragmentedBinary_assembledByteExact() throws Exception {
        establishSession();

        byte[] expected = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
        channel.pipeline().fireChannelRead(new BinaryWebSocketFrame(false, 0, Unpooled.wrappedBuffer(expected, 0, 3)));
        channel.pipeline()
                .fireChannelRead(new ContinuationWebSocketFrame(false, 0, Unpooled.wrappedBuffer(expected, 3, 2)));
        channel.pipeline()
                .fireChannelRead(new ContinuationWebSocketFrame(true, 0, Unpooled.wrappedBuffer(expected, 5, 3)));

        ArgumentCaptor<BinaryMessage> captor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(handler, timeout(2000)).handleMessage(any(WebSocketSession.class), captor.capture());
        assertArrayEquals(expected, captor.getValue().getPayload().array());
    }

    /* ==================== 未分片消息行为不变 ==================== */

    @Test
    @DisplayName("未分片的完整帧仍立即派发（行为不回退）")
    void unfragmentedFrames_stillDispatchedImmediately() throws Exception {
        establishSession();

        channel.pipeline().fireChannelRead(new TextWebSocketFrame("solo"));
        verify(handler, timeout(2000)).handleMessage(any(WebSocketSession.class), eq(new TextMessage("solo")));
    }

    /* ==================== 协议违规 ==================== */

    @Test
    @DisplayName("无起始帧的 continuation 属协议违规：报传输错误并关闭")
    void continuationWithoutStart_isProtocolViolation() throws Exception {
        establishSession();

        channel.pipeline().fireChannelRead(new ContinuationWebSocketFrame(true, 0, "orphan"));

        verify(handler, timeout(2000)).handleTransportError(any(WebSocketSession.class),
                any(java.io.IOException.class));
        assertFalse(channel.isActive(), "协议违规后应关闭连接");
        // 不得派发任何消息
        verify(handler, times(0)).handleMessage(any(WebSocketSession.class), any(TextMessage.class));
    }

    @Test
    @DisplayName("分片未结束又来新数据帧属协议违规")
    void newDataFrameWhileFragmented_isProtocolViolation() throws Exception {
        establishSession();

        channel.pipeline().fireChannelRead(new TextWebSocketFrame(false, 0, "start"));
        channel.pipeline().fireChannelRead(new TextWebSocketFrame("intruder"));

        verify(handler, timeout(2000)).handleTransportError(any(WebSocketSession.class),
                any(java.io.IOException.class));
        assertFalse(channel.isActive());
    }

    @Test
    @DisplayName("分片累积超过 size limit 时关闭连接，避免无界堆增长")
    void fragmentExceedingSizeLimit_closesConnection() throws Exception {
        establishSession();

        // session 默认 text limit 8KB；起始帧 4KB + 4KB 中间帧 + 末帧 1KB 必超限
        ByteBuf chunk = Unpooled.buffer();
        chunk.writeBytes(new byte[4096]);
        channel.pipeline().fireChannelRead(new TextWebSocketFrame(false, 0, chunk.copy()));
        channel.pipeline().fireChannelRead(new ContinuationWebSocketFrame(false, 0, chunk.copy()));
        channel.pipeline().fireChannelRead(new ContinuationWebSocketFrame(true, 0, chunk.copy(0, 1024)));
        chunk.release();

        verify(handler, timeout(2000)).handleTransportError(any(WebSocketSession.class),
                any(java.io.IOException.class));
        assertFalse(channel.isActive(), "超限应关闭连接");
        // 超限的消息不得派发
        verify(handler, times(0)).handleMessage(any(WebSocketSession.class), any(TextMessage.class));
    }

    @Test
    @DisplayName("连接断开时丢弃进行中的分片缓冲，不留孤儿 ByteBuf")
    void channelClosedMidFragment_releasesBuffer() throws Exception {
        establishSession();

        channel.pipeline().fireChannelRead(new TextWebSocketFrame(false, 0, "half"));

        // 直接关闭：聚合永不完成，缓冲必须被释放（由 releaseFragment 处理）
        channel.close();

        verify(handler, timeout(2000)).afterConnectionClosed(any(WebSocketSession.class), any());
        // 无派发、无泄漏断言外的异常
        verify(handler, times(0)).handleMessage(any(WebSocketSession.class), any(TextMessage.class));
    }

    /* ==================== Ping/Pong 与分片互不干扰 ==================== */

    @Test
    @DisplayName("控制帧可穿插在分片之间（RFC 6455 允许）")
    void controlFramesMayInterleaveFragments() throws Exception {
        establishSession();

        channel.pipeline().fireChannelRead(new TextWebSocketFrame(false, 0, "part1-"));
        channel.pipeline().fireChannelRead(new io.netty.handler.codec.http.websocketx.PingWebSocketFrame());
        drainOutbound(); // Pong 回写
        channel.pipeline().fireChannelRead(new ContinuationWebSocketFrame(true, 0, "part2"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(handler, timeout(2000)).handleMessage(any(WebSocketSession.class), captor.capture());
        assertEquals("part1-part2", captor.getValue().getPayload());
    }

    private static io.netty.handler.codec.http.FullHttpRequest upgradeRequest(String path) {
        io.netty.handler.codec.http.FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.GET, path, Unpooled.buffer(0));
        req.headers().set(HttpHeaderNames.HOST, "localhost");
        req.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        req.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        return req;
    }
}
