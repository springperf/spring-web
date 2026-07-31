package io.springperf.web.core.async.stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.EventExecutor;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.NettyServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NettyStreamSenderTest {

    @Mock
    StreamEmitter emitter;
    @Mock
    PerfAsyncWebRequest asyncWebRequest;
    @Mock
    NettyServerHttpResponse nativeResponse;
    @Mock
    ChannelHandlerContext ctx;
    @Mock
    Channel channel;
    @Mock
    EventExecutor eventLoop;
    @Mock
    ChannelFuture channelFuture;

    @Captor
    ArgumentCaptor<DefaultHttpContent> httpContentCaptor;
    @Captor
    ArgumentCaptor<LastHttpContent> lastHttpContentCaptor;

    private Field encodeField;

    @BeforeEach
    void setUp() throws Exception {
        when(emitter.getMaxFlushBytes()).thenReturn(4096);
        encodeField = StreamEmitter.class.getDeclaredField("encodeToString");
        encodeField.setAccessible(true);

        when(asyncWebRequest.getNativeResponse()).thenReturn(nativeResponse);
        when(nativeResponse.getCtx()).thenReturn(ctx);
        when(nativeResponse.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        when(ctx.channel()).thenReturn(channel);
        when(ctx.executor()).thenReturn(eventLoop);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        when(eventLoop.inEventLoop()).thenReturn(true);
        when(channel.isActive()).thenReturn(true);
        when(channel.isWritable()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
    }

    // ==================== StringNettyStreamSender ====================

    @Test
    void stringSender_constructor_wiresChannelAndQueue() throws Exception {
        encodeField.setBoolean(emitter, true);
        StringNettyStreamSender sender = new StringNettyStreamSender(emitter, asyncWebRequest);
        assertEquals(0, sender.queueSize());
        assertNotNull(sender);
    }

    @Test
    void stringSender_send_encodesAndWritesToChannel() throws Exception {
        encodeField.setBoolean(emitter, true);
        when(emitter.encodeToString(any())).thenReturn("hello");
        when(channel.write(any())).thenReturn(channelFuture);
        StringNettyStreamSender sender = new StringNettyStreamSender(emitter, asyncWebRequest);

        sender.send("hello");

        verify(channel, atLeastOnce()).write(httpContentCaptor.capture());
        ByteBuf written = httpContentCaptor.getValue().content();
        assertEquals("hello", written.toString(StandardCharsets.UTF_8));
    }

    @Test
    void stringSender_send_emptyString_skips() throws Exception {
        encodeField.setBoolean(emitter, true);
        when(emitter.encodeToString(any())).thenReturn("");
        StringNettyStreamSender sender = new StringNettyStreamSender(emitter, asyncWebRequest);

        sender.send("");

        verify(channel, never()).write(any());
    }

    @Test
    void stringSender_send_nullEncodeResult_skips() throws Exception {
        encodeField.setBoolean(emitter, true);
        when(emitter.encodeToString(any())).thenReturn(null);
        StringNettyStreamSender sender = new StringNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data");

        verify(channel, never()).write(any());
    }

    @Test
    void stringSender_send_closedChannel_throws() throws Exception {
        encodeField.setBoolean(emitter, true);
        when(channel.isActive()).thenReturn(false);
        StringNettyStreamSender sender = new StringNettyStreamSender(emitter, asyncWebRequest);

        assertThrows(IOException.class, () -> sender.send("data"));
    }

    @Test
    void stringSender_drain_flushesChannel() throws Exception {
        encodeField.setBoolean(emitter, true);
        when(emitter.encodeToString(any())).thenReturn("data");
        when(channel.write(any())).thenReturn(channelFuture);
        StringNettyStreamSender sender = new StringNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data");

        verify(channel, atLeastOnce()).flush();
    }

    @Test
    void stringSender_complete_writesLastContent() throws Exception {
        encodeField.setBoolean(emitter, true);
        when(emitter.encodeToString(any())).thenReturn("data");
        when(channel.write(any())).thenReturn(channelFuture);
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);
        StringNettyStreamSender sender = new StringNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data");
        sender.complete(true, null);

        verify(channel).writeAndFlush(lastHttpContentCaptor.capture());
        assertTrue(lastHttpContentCaptor.getValue() instanceof LastHttpContent);
    }

    @Test
    void stringSender_completed_throws() throws Exception {
        encodeField.setBoolean(emitter, true);
        when(emitter.encodeToString(any())).thenReturn("data");
        when(channel.write(any())).thenReturn(channelFuture);
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);
        StringNettyStreamSender sender = new StringNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data");
        sender.complete(true, null);

        assertThrows(IOException.class, () -> sender.send("more"));
    }

    @Test
    void stringSender_writeCharSequence_utf8() throws Exception {
        encodeField.setBoolean(emitter, true);
        StringNettyStreamSender sender = new StringNettyStreamSender(emitter, asyncWebRequest);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(64);
        sender.writeCharSequence(buf, "你好");
        assertEquals("你好", buf.toString(StandardCharsets.UTF_8));
        buf.release();
    }

    @Test
    void stringSender_writeCharSequence_ascii() throws Exception {
        encodeField.setBoolean(emitter, true);
        when(nativeResponse.getCharacterEncoding()).thenReturn(StandardCharsets.US_ASCII);
        StringNettyStreamSender sender = new StringNettyStreamSender(emitter, asyncWebRequest);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(64);
        sender.writeCharSequence(buf, "hello");
        assertEquals("hello", buf.toString(StandardCharsets.US_ASCII));
        buf.release();
    }

    // ==================== BytesNettyStreamSender ====================

    @Test
    void bytesSender_send_writesToChannel() throws Exception {
        encodeField.setBoolean(emitter, false);
        byte[] data = "world".getBytes(StandardCharsets.UTF_8);
        when(emitter.encodeToBytes(any())).thenReturn(data);
        when(channel.write(any())).thenReturn(channelFuture);
        BytesNettyStreamSender sender = new BytesNettyStreamSender(emitter, asyncWebRequest);

        sender.send(data);

        verify(channel, atLeastOnce()).write(httpContentCaptor.capture());
        ByteBuf written = httpContentCaptor.getValue().content();
        assertEquals("world", written.toString(StandardCharsets.UTF_8));
    }

    @Test
    void bytesSender_send_empty_skips() throws Exception {
        encodeField.setBoolean(emitter, false);
        when(emitter.encodeToBytes(any())).thenReturn(new byte[0]);
        BytesNettyStreamSender sender = new BytesNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data");

        verify(channel, never()).write(any());
    }

    @Test
    void bytesSender_send_null_skips() throws Exception {
        encodeField.setBoolean(emitter, false);
        when(emitter.encodeToBytes(any())).thenReturn(null);
        BytesNettyStreamSender sender = new BytesNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data");

        verify(channel, never()).write(any());
    }

    @Test
    void bytesSender_send_closedChannel_throws() throws Exception {
        encodeField.setBoolean(emitter, false);
        when(channel.isActive()).thenReturn(false);
        BytesNettyStreamSender sender = new BytesNettyStreamSender(emitter, asyncWebRequest);

        assertThrows(IOException.class, () -> sender.send("data"));
    }

    @Test
    void bytesSender_complete_writesLastContent() throws Exception {
        encodeField.setBoolean(emitter, false);
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        when(emitter.encodeToBytes(any())).thenReturn(data);
        when(channel.write(any())).thenReturn(channelFuture);
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);
        BytesNettyStreamSender sender = new BytesNettyStreamSender(emitter, asyncWebRequest);

        sender.send("test");
        sender.complete(true, null);

        verify(channel).writeAndFlush(lastHttpContentCaptor.capture());
        assertTrue(lastHttpContentCaptor.getValue() instanceof LastHttpContent);
    }

    @Test
    void bytesSender_completed_throws() throws Exception {
        encodeField.setBoolean(emitter, false);
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        when(emitter.encodeToBytes(any())).thenReturn(data);
        when(channel.write(any())).thenReturn(channelFuture);
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);
        BytesNettyStreamSender sender = new BytesNettyStreamSender(emitter, asyncWebRequest);

        sender.send("test");
        sender.complete(true, null);

        assertThrows(IOException.class, () -> sender.send("more"));
    }
}