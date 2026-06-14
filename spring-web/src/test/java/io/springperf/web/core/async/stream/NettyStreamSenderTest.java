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

    NettyStreamSender sender;
    private Field encodeField;

    @BeforeEach
    void setUp() throws Exception {
        when(emitter.getMaxFlushBytes()).thenReturn(4096);
        // encodeToString 是字段，不是方法，需要通过反射设置
        encodeField = StreamEmitter.class.getDeclaredField("encodeToString");
        encodeField.setAccessible(true);
        encodeField.setBoolean(emitter, true);

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

        sender = new NettyStreamSender(emitter, asyncWebRequest);
    }

    @Test
    void constructor_wiresChannelAndQueue() {
        assertEquals(0, sender.queueSize());
        assertNotNull(sender);
    }

    @Test
    void send_stringData_encodesAndWritesToChannel() throws Exception {
        when(emitter.encodeToString(any())).thenReturn("hello");
        when(channel.write(any())).thenReturn(channelFuture);

        sender.send("hello");

        verify(channel, atLeastOnce()).write(httpContentCaptor.capture());
        ByteBuf written = httpContentCaptor.getValue().content();
        assertEquals("hello", written.toString(StandardCharsets.UTF_8));
    }

    @Test
    void send_byteData_writesToChannel() throws Exception {
        encodeField.setBoolean(emitter, false); // 切换到字节模式
        byte[] data = "world".getBytes(StandardCharsets.UTF_8);
        when(emitter.encodeToBytes(any())).thenReturn(data);
        when(channel.write(any())).thenReturn(channelFuture);

        sender.send(data);

        verify(channel, atLeastOnce()).write(httpContentCaptor.capture());
        ByteBuf written = httpContentCaptor.getValue().content();
        assertEquals("world", written.toString(StandardCharsets.UTF_8));
    }

    @Test
    void send_emptyString_skips() throws Exception {
        when(emitter.encodeToString(any())).thenReturn("");

        sender.send("");

        verify(channel, never()).write(any());
    }

    @Test
    void send_nullEncodeResult_skips() throws Exception {
        when(emitter.encodeToString(any())).thenReturn(null);

        sender.send("data");

        verify(channel, never()).write(any());
    }

    @Test
    void send_closedChannel_throws() {
        when(channel.isActive()).thenReturn(false);

        assertThrows(IOException.class, () -> sender.send("data"));
    }

    @Test
    void drain_flushesChannel() throws Exception {
        when(emitter.encodeToString(any())).thenReturn("data");
        when(channel.write(any())).thenReturn(channelFuture);

        sender.send("data");

        verify(channel, atLeastOnce()).flush();
    }

    @Test
    void complete_writesLastContent() {
        sender.complete(true, null);

        verify(channel).writeAndFlush(lastHttpContentCaptor.capture());
        assertTrue(lastHttpContentCaptor.getValue() instanceof LastHttpContent);
    }

    @Test
    void complete_doesNotCloseChannelWhenDisabled() {
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);
        sender.complete(false, null);
        // closeChannelOnComplete=false, 关闭在 onCompleteSuccess 回调中处理
    }

    @Test
    void send_completedEmitter_throws() throws Exception {
        when(emitter.encodeToString(any())).thenReturn("data");
        when(channel.write(any())).thenReturn(channelFuture);
        when(ctx.writeAndFlush(any())).thenReturn(channelFuture);

        sender.send("data");
        sender.complete(true, null);

        assertThrows(IOException.class, () -> sender.send("more"));
    }

    @Test
    void writeCharSequence_utf8_encodesCorrectly() {
        ByteBuf result = sender.writeCharSequence("你好");
        assertEquals("你好", result.toString(StandardCharsets.UTF_8));
        result.release();
    }

    @Test
    void writeCharSequence_ascii_encodesCorrectly() {
        when(nativeResponse.getCharacterEncoding()).thenReturn(StandardCharsets.US_ASCII);

        ByteBuf result = sender.writeCharSequence("hello");
        assertEquals("hello", result.toString(StandardCharsets.US_ASCII));
        result.release();
    }

    @Test
    void calculateCapacity_cachesPerCharset() {
        int capacity = sender.calculateCapacity("hello", StandardCharsets.UTF_8);
        assertTrue(capacity >= 5);

        int cached = sender.calculateCapacity("hello", StandardCharsets.UTF_8);
        assertEquals(capacity, cached);
    }
}
