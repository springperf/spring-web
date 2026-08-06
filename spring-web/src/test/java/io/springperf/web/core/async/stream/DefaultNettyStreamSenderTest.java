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

        verify(channel, atLeast(2)).writeAndFlush(any(DefaultHttpContent.class));
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
        sender.complete(true, null);

        verify(channel, atLeastOnce()).writeAndFlush(lastHttpContentCaptor.capture());
        assertTrue(lastHttpContentCaptor.getValue() instanceof LastHttpContent);
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
        DefaultHttpContent content = httpContentCaptor.getValue();
        assertEquals("good", content.content().toString(StandardCharsets.UTF_8));
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
        for (DefaultHttpContent c : httpContentCaptor.getAllValues()) {
            written += c.content().readableBytes();
        }
        assertEquals(10, written, "所有已发送数据必须完整写出，不允许截断");
    }
}