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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultNettyStreamSenderTest {

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
    // 用 HttpContent（DefaultHttpContent/LastHttpContent 的父接口）作为捕获类型：
    // Mockito 4（SB 2.7）的 ArgumentCaptor 捕获所有参数（包括 LastHttpContent），
    // 而 Mockito 5（master）只捕获匹配类型的参数。统一用父接口捕获 + instanceof 过滤，
    // 保证两种 Mockito 版本下行为一致。
    ArgumentCaptor<HttpContent> httpContentCaptor;
    @Captor
    ArgumentCaptor<LastHttpContent> lastHttpContentCaptor;

    @BeforeEach
    void setUp() throws Exception {
        when(emitter.getMaxFlushBytes()).thenReturn(4096);
        when(asyncWebRequest.getNativeResponse()).thenReturn(nativeResponse);
        when(nativeResponse.getCtx()).thenReturn(ctx);
        when(nativeResponse.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        // onAllDataWritten 的终止块单一所有者守卫：mock 默认返回 false 会直接 return（不写 LastHttpContent）
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
    void constructor_wiresChannelAndQueue() {
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);
        assertEquals(0, sender.queueSize());
        assertNotNull(sender);
    }

    @Test
    void send_queuesDataAndDrainWritesToChannel() throws Exception {
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(emitter).encode(any(), any());
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data");

        verify(channel, atLeastOnce()).writeAndFlush(httpContentCaptor.capture());
        ByteBuf written = httpContentCaptor.getValue().content();
        assertEquals("hello", written.toString(StandardCharsets.UTF_8));
        written.release();
    }

    @Test
    void send_multipleItems_batchWritesAtThreshold() throws Exception {
        int chunkSize = 3000;
        byte[] chunk = new byte[chunkSize];
        for (int i = 0; i < chunkSize; i++) chunk[i] = (byte) 'a';
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write(chunk);
            return null;
        }).when(emitter).encode(any(), any());
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        sender.send("first");
        sender.send("second");

        verify(channel, atLeast(2)).writeAndFlush(httpContentCaptor.capture());
        for (HttpContent content : httpContentCaptor.getAllValues()) {
            if (content instanceof DefaultHttpContent) {
                content.content().release();
            }
        }
    }

    @Test
    void send_closedChannel_throws() {
        when(channel.isActive()).thenReturn(false);
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        assertThrows(IOException.class, () -> sender.send("data"));
    }

    @Test
    void complete_writesLastContent() throws Exception {
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("data".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(emitter).encode(any(), any());
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data");
        // 首参 closeChannelOnComplete=false：正常结束 → 写 LastHttpContent（true 走的是「关闭通道」分支，见 complete_withCloseChannelOnComplete_terminates）
        sender.complete(false, null);

        // 2.7.x 适配：Mockito 4 的 ArgumentCaptor 会捕获所有入参（含 DefaultHttpContent），
        // 故不能用 ArgumentCaptor<LastHttpContent> 直接取值，改用父接口捕获 + instanceof 过滤。
        verify(channel, atLeastOnce()).writeAndFlush(httpContentCaptor.capture());
        LastHttpContent last = null;
        for (HttpContent c : httpContentCaptor.getAllValues()) {
            if (c instanceof LastHttpContent) {
                last = (LastHttpContent) c;
            }
        }
        assertNotNull(last, "complete 必须写出 LastHttpContent");
        last.release();
    }

    @Test
    void drain_encodeError_rollbacksWriterIndex() throws Exception {
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("good".getBytes(StandardCharsets.UTF_8));
            return null;
        }).doThrow(new RuntimeException("encode failed"))
        .when(emitter).encode(any(), any());
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        sender.send("ok");
        sender.send("bad");

        verify(channel, atLeastOnce()).writeAndFlush(httpContentCaptor.capture());
        DefaultHttpContent content = (DefaultHttpContent) httpContentCaptor.getValue();
        assertEquals("good", content.content().toString(StandardCharsets.UTF_8));
        content.content().release();
    }

    @Test
    void queueSize_returnsCorrectCount() throws Exception {
        when(channel.isWritable()).thenReturn(true);
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data");
        assertEquals(0, sender.queueSize());
    }

    @Test
    void scheduleDrain_fromAppThread_executesDrain() throws Exception {
        when(eventLoop.inEventLoop()).thenReturn(false);
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("data".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(emitter).encode(any(), any());
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        sender.send("data");

        verify(eventLoop).execute(any(Runnable.class));
    }

    @Test
    void completed_send_throws() throws Exception {
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);
        sender.complete(true, null);

        assertThrows(IOException.class, () -> sender.send("data"));
    }

    @Test
    void drain_notWritable_skipsWrite() throws Exception {
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("data".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(emitter).encode(any(), any());
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        when(channel.isWritable()).thenReturn(false);
        sender.send("data");

        verify(channel, never()).writeAndFlush(any(DefaultHttpContent.class));
    }

    @Test
    void complete_notWritable_mustNotDiscardQueuedData_waitsForWritable() throws Exception {
        // 背压场景：channel 不可写（写缓冲越过高水位），且生产者已完成
        when(channel.isWritable()).thenReturn(false);
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(emitter).encode(any(), any());
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        sender.send("a");
        sender.send("b");
        sender.complete(false, null);

        // 期望：数据保留在队列等待 writable 恢复，绝不丢弃、不提前写 LastHttpContent
        assertEquals(2, sender.queueSize());
        verify(channel, never()).writeAndFlush(any(DefaultHttpContent.class));
        verify(channel, never()).writeAndFlush(any(LastHttpContent.class));

        // 模拟 BackpressureHandler false->true 触发 writable callback (= scheduleDrain)
        when(channel.isWritable()).thenReturn(true);
        sender.scheduleDrain();

        // 期望：恢复后完整写出全部数据 + LastHttpContent，无截断
        verify(channel, atLeastOnce()).writeAndFlush(httpContentCaptor.capture());
        verify(channel, atLeastOnce()).writeAndFlush(lastHttpContentCaptor.capture());
        assertEquals(0, sender.queueSize());
        int written = 0;
        for (HttpContent c : httpContentCaptor.getAllValues()) {
            // 只统计数据帧，排除 LastHttpContent（Mockito 4 下 captor 会捕获到它）
            if (c instanceof DefaultHttpContent) {
                written += c.content().readableBytes();
            }
        }
        assertEquals(10, written, "所有已发送数据必须完整写出，不允许截断");
    }

    @Test
    void drain_allEncodesFail_mustNotWriteEmptyContent() throws Exception {
        doThrow(new RuntimeException("encode failed")).when(emitter).encode(any(), any());
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        sender.send("bad1");
        sender.send("bad2");

        verify(channel, never()).writeAndFlush(any(DefaultHttpContent.class));
        assertEquals(0, sender.queueSize(), "队列应已被 drain 消费");
        verify(emitter, times(2)).onEncodeError(any(), any());
    }

    /*
     * 上面那条 {@code never()} 的正对照：同一套装置下正常 encode **必须**写出内容， 否则"没写出空帧"可能只是因为装置根本没跑到 drain。
     */
    @Test
    void drain_nonEmptyBatch_writesContent_positiveControl() throws Exception {
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("ok".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(emitter).encode(any(), any());
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        sender.send("a");

        verify(channel).writeAndFlush(httpContentCaptor.capture());
        assertEquals("ok", httpContentCaptor.getValue().content().toString(StandardCharsets.UTF_8));
        httpContentCaptor.getValue().content().release();
    }
}