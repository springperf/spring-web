package io.springperf.web.websocket.server;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link NettyWebSocketSession} 的消息发送类型转换、关闭、访问器与值语义。
 */
class NettyWebSocketSessionTest {

    private URI uri = URI.create("ws://localhost:8080/ws");

    private NettyWebSocketSession newSession(EmbeddedChannel channel) {
        return new NettyWebSocketSession(
                channel, uri, new SpringHeadersAdapter(new io.netty.handler.codec.http.DefaultHttpHeaders(false)),
                "chat", new InetSocketAddress("127.0.0.1", 8080),
                new InetSocketAddress("192.168.1.5", 5000), null, null);
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
    void sendBinaryMessage_writesBinaryFrame() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyWebSocketSession session = newSession(channel);
        ByteBuffer payload = ByteBuffer.wrap(new byte[]{1, 2, 3});
        session.sendMessage(new BinaryMessage(payload));
        channel.runPendingTasks();

        WebSocketFrame out = channel.readOutbound();
        assertTrue(out instanceof BinaryWebSocketFrame);
        assertArrayEquals(new byte[]{1, 2, 3}, io.netty.buffer.ByteBufUtil.getBytes(out.content()));
        out.release();
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
        assertThrows(java.io.IOException.class,
                () -> session.sendMessage(new TextMessage("x")));
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
        c1.finishAndReleaseAll();
        c2.finishAndReleaseAll();
    }
}