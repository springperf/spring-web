package io.springperf.web.core.async.stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.util.concurrent.EventExecutor;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.NettyServerHttpResponse;

/** drain 的失活竞态、超大单条、空负载与批量缓冲余量不足分支。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EarlyEncodeNettyStreamSenderEdgeCaseTest {

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
    ArgumentCaptor<DefaultHttpContent> contentCaptor;

    @BeforeEach
    void setUp() throws Exception {
        when(emitter.getMaxFlushBytes()).thenReturn(4096);
        when(asyncWebRequest.getNativeResponse()).thenReturn(nativeResponse);
        when(nativeResponse.getCtx()).thenReturn(ctx);
        when(nativeResponse.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        when(nativeResponse.markStreamCompleted()).thenReturn(true);
        when(ctx.channel()).thenReturn(channel);
        when(ctx.executor()).thenReturn(eventLoop);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        when(eventLoop.inEventLoop()).thenReturn(true);
        when(channel.isActive()).thenReturn(true);
        when(channel.isWritable()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
    }

    @Test
    void inactiveChannelAfterCompleteResetsWipAndClosesChannel() throws Exception {
        when(channel.isActive()).thenReturn(false);
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);

        sender.complete(false, null);

        assertEquals(0, sender.wip.get(), "失活分支必须复位 wip，否则后续 drain 永久挂起");
        verify(channel).close();
        verify(nativeResponse).setWritableCallback(null);
    }

    @Test
    void singleOversizedPayloadIsFlushedWithoutBuffering() throws Exception {
        when(emitter.getMaxFlushBytes()).thenReturn(4);
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);

        sender.send("abcdefgh".getBytes(StandardCharsets.UTF_8));

        verify(channel).writeAndFlush(contentCaptor.capture());
        ByteBuf written = contentCaptor.getValue().content();
        assertEquals("abcdefgh", written.toString(StandardCharsets.UTF_8));
        written.release();
    }

    @Test
    void emptyPayloadInBatchIsSkipped() throws Exception {
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);

        sender.sendAll(Arrays.asList(new byte[0], "x".getBytes(StandardCharsets.UTF_8)));

        verify(channel).writeAndFlush(contentCaptor.capture());
        ByteBuf written = contentCaptor.getValue().content();
        assertEquals("x", written.toString(StandardCharsets.UTF_8));
        written.release();
    }

    @Test
    void batchBufferWithInsufficientRemainingSpaceIsFlushedAndReallocated() throws Exception {
        when(emitter.getMaxFlushBytes()).thenReturn(4);
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);

        sender.sendAll(java.util.Arrays.asList("abc".getBytes(StandardCharsets.UTF_8), "def".getBytes(StandardCharsets.UTF_8)));

        verify(channel, times(2)).writeAndFlush(contentCaptor.capture());
        List<DefaultHttpContent> written = contentCaptor.getAllValues();
        assertEquals("abc", written.get(0).content().toString(StandardCharsets.UTF_8));
        assertEquals("def", written.get(1).content().toString(StandardCharsets.UTF_8));
        written.forEach(DefaultHttpContent::release);
    }
}
