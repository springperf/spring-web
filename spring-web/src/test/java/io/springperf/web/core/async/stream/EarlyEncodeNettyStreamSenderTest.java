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
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EarlyEncodeNettyStreamSenderTest {

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

    @BeforeEach
    void setUp() throws Exception {
        when(emitter.getMaxFlushBytes()).thenReturn(4096);
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

    @Test
    void constructor_wiresChannelAndQueue() {
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);
        assertEquals(0, sender.queueSize());
        assertNotNull(sender);
    }

    @Test
    void send_encodesAndWritesToChannel() throws Exception {
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);

        sender.send("hello".getBytes(StandardCharsets.UTF_8));

        verify(channel, atLeastOnce()).writeAndFlush(httpContentCaptor.capture());
        ByteBuf written = httpContentCaptor.getValue().content();
        assertEquals("hello", written.toString(StandardCharsets.UTF_8));
        written.release();
    }

    @Test
    void send_emptyResult_skips() throws Exception {
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);

        sender.send(new byte[0]);

        verify(channel, never()).writeAndFlush(any());
    }

    @Test
    void send_closedChannel_throws() {
        when(channel.isActive()).thenReturn(false);
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);

        assertThrows(IOException.class, () -> sender.send("data".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void drain_flushesChannel() throws Exception {
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data".getBytes(StandardCharsets.UTF_8));

        verify(channel, atLeastOnce()).writeAndFlush(httpContentCaptor.capture());
        for (DefaultHttpContent content : httpContentCaptor.getAllValues()) {
            content.content().release();
        }
    }

    @Test
    void complete_writesLastContent() throws Exception {
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data".getBytes(StandardCharsets.UTF_8));
        sender.complete(true, null);

        verify(channel, atLeastOnce()).writeAndFlush(lastHttpContentCaptor.capture());
        assertTrue(lastHttpContentCaptor.getValue() instanceof LastHttpContent);
        lastHttpContentCaptor.getValue().release();
    }

    @Test
    void completed_throws() throws Exception {
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data".getBytes(StandardCharsets.UTF_8));
        sender.complete(true, null);

        assertThrows(IOException.class, () -> sender.send("more".getBytes(StandardCharsets.UTF_8)));
    }
}