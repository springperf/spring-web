package io.springperf.web.websocket.server;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link WebSocketFrameDispatcher} 的三条边界路径：握手前收到帧、跨多帧累计超限、空载荷 Pong。
 * <p>
 * 都能在「握手完成后的正常收发」之外触发，因此单列。
 * </p>
 */
class WebSocketFrameDispatcherTest {

    private EmbeddedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null && channel.isOpen()) {
            channel.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel newChannel(WebSocketHandler handler, NettyWebSocketSession[] outSession) {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(new WebSocketRoutingHandler(Map.of()));
        NettyWebSocketSession session = new NettyWebSocketSession(ch, URI.create("/ws"), new HttpHeaders(), null, null,
                null, null, handler);
        ch.attr(WebSocketRoutingHandler.CONNECTION_KEY).set(new WebSocketRoutingHandler.WsConnection(session, handler));
        if (outSession != null) {
            outSession[0] = session;
        }
        return ch;
    }

    @Test
    void frameBeforeHandshake_closesChannelAndReleasesFrame() {
        channel = new EmbeddedChannel();
        channel.pipeline().addLast(new WebSocketRoutingHandler(Map.of()));

        TextWebSocketFrame frame = new TextWebSocketFrame("early");
        channel.writeInbound(frame);

        assertFalse(channel.isOpen(), "未完成握手就收到帧必须直接断开");
        assertEquals(0, frame.refCnt(), "这条早退路径也必须释放帧引用（此处曾泄漏）");
    }

    @Test
    void accumulatedFragmentsExceedingLimit_closesSession() throws Exception {
        WebSocketHandler handler = mock(WebSocketHandler.class);
        NettyWebSocketSession[] sessionHolder = new NettyWebSocketSession[1];
        channel = newChannel(handler, sessionHolder);
        sessionHolder[0].setTextMessageSizeLimit(10);
        WebSocketRoutingHandler.WsConnection connection = channel.attr(WebSocketRoutingHandler.CONNECTION_KEY).get();

        // 首片 6 字节：未超限，进入聚合
        channel.writeInbound(new TextWebSocketFrame(false, 0, "aaaaaa"));
        assertNotNull(connection.fragment, "FIN=0 的首片应建立聚合状态");

        // 续片再 6 字节：累计 12 > 10，必须按累计值拒绝（单帧都没超限）
        channel.writeInbound(new ContinuationWebSocketFrame(false, 0, "bbbbbb"));

        assertNull(connection.fragment, "超限后必须复位聚合状态并释放缓冲");
        assertFalse(channel.isOpen());
        verify(handler).handleTransportError(any(), any());
        verify(handler, never()).handleMessage(any(), any());
    }

    @Test
    void emptyPongPayload_reusesSharedBuffer() throws Exception {
        WebSocketHandler handler = mock(WebSocketHandler.class);
        channel = newChannel(handler, null);

        channel.writeInbound(new PongWebSocketFrame());
        channel.writeInbound(new PongWebSocketFrame());

        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(handler, times(2)).handleMessage(any(), captor.capture());

        List<ByteBuffer> payloads = captor.getAllValues().stream().map(message -> ((PongMessage) message).getPayload())
                .toList();
        assertEquals(0, payloads.get(0).remaining(), "空 Pong 交给业务的载荷应为空");
        assertSame(payloads.get(0), payloads.get(1), "空载荷应复用共享常量，避免每帧分配");
    }

    @Test
    void pongWithPayload_copiesBytesBecauseFrameIsReleased() throws Exception {
        WebSocketHandler handler = mock(WebSocketHandler.class);
        channel = newChannel(handler, null);

        channel.writeInbound(new PongWebSocketFrame(io.netty.buffer.Unpooled.wrappedBuffer(new byte[] { 7, 8 })));

        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(handler).handleMessage(any(), captor.capture());
        ByteBuffer payload = ((PongMessage) captor.getValue()).getPayload();
        assertEquals(2, payload.remaining());
        assertEquals(7, payload.get(0));
        assertEquals(8, payload.get(1));
    }
}
