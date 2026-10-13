package io.springperf.web.core.async.stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
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

    @BeforeEach
    void setUp() throws Exception {
        when(emitter.getMaxFlushBytes()).thenReturn(4096);
        when(asyncWebRequest.getNativeResponse()).thenReturn(nativeResponse);
        // onAllDataWritten 先抢占终止块写入权；替身响应需显式放行，否则不写 LastHttpContent
        when(nativeResponse.markStreamCompleted()).thenReturn(true);
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

        // 2.7.x 适配：Mockito 4 的 ArgumentCaptor 会捕获所有入参（含 DefaultHttpContent 数据帧），
        // 故用父接口捕获 + instanceof 过滤，不能直接按 LastHttpContent 取值。
        ArgumentCaptor<HttpContent> captor = ArgumentCaptor.forClass(HttpContent.class);
        verify(channel, atLeastOnce()).writeAndFlush(captor.capture());
        LastHttpContent last = null;
        for (HttpContent c : captor.getAllValues()) {
            if (c instanceof LastHttpContent) {
                last = (LastHttpContent) c;
            }
        }
        assertNotNull(last, "complete 必须写出 LastHttpContent");
        // 数据帧 + 终止块的所有权在 mock channel 上无人接管（生产环境由 Netty 写出后释放），
        // 必须由测试自行释放，否则 paranoid 泄漏检测报 LEAK。
        releaseWrittenContents(captor);
    }

    @Test
    void completed_throws() throws Exception {
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data".getBytes(StandardCharsets.UTF_8));
        sender.complete(true, null);

        assertThrows(IOException.class, () -> sender.send("more".getBytes(StandardCharsets.UTF_8)));

        ArgumentCaptor<HttpContent> captor = ArgumentCaptor.forClass(HttpContent.class);
        verify(channel, atLeastOnce()).writeAndFlush(captor.capture());
        releaseWrittenContents(captor);
    }

    /**
     * 释放 mock channel 收到的全部内容帧缓冲。
     * <p>生产环境中帧写出后由 Netty 接管并释放；mock channel 不会接管，
     * 测试侧不释放则这块池化 ByteBuf 在 GC 时被 paranoid 泄漏检测报为 LEAK
     * （{@code ByteBuf.release() was not called before it's garbage-collected}）。</p>
     */
    private static void releaseWrittenContents(ArgumentCaptor<HttpContent> captor) {
        for (HttpContent content : captor.getAllValues()) {
            // LastHttpContent.EMPTY_LAST_CONTENT 基于 Unpooled.EMPTY_BUFFER，release 为无副作用空操作
            if (content.content().refCnt() > 0) {
                content.content().release();
            }
        }
    }
}