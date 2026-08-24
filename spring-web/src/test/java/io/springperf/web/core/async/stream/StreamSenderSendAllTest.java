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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归：{@link StreamSender#sendAll(Collection)} 批量交付语义。
 * <p>
 * 背景（SSE CPU 热点分析）：{@code StreamEmitter.initialize} 在 EventLoop 线程上
 * 逐条 {@code send()} 时，每条 send 的 scheduleDrain 因 {@code inEventLoop()} 直接同步
 * drain 并立即 flush 当前队列（仅刚入队的一条），100 条 SSE 消息产生 100 次小
 * writeAndFlush，破坏 drain() 里 batchBuf + maxFlushBytes 的批量编码设计。
 * <p>
 * 本测试验证 sendAll 修复后的语义：
 * <ul>
 *   <li>全部元素一次性入队（drain 前 queueSize 反映批量大小）</li>
 *   <li>末尾仅调度一次 drain（对比逐条 send 逐条 drain）</li>
 *   <li>多元素在单次 drain 中合并为一次 writeAndFlush（不超过 maxFlushBytes）</li>
 *   <li>earlyEncode（byte[]）路径同样批量生效</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamSenderSendAllTest {

    private static final int MAX_FLUSH = 4096;

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
        when(emitter.getMaxFlushBytes()).thenReturn(MAX_FLUSH);
        when(asyncWebRequest.getNativeResponse()).thenReturn(nativeResponse);
        when(nativeResponse.getCtx()).thenReturn(ctx);
        when(nativeResponse.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        when(ctx.channel()).thenReturn(channel);
        when(ctx.executor()).thenReturn(eventLoop);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        // 模拟真实热点：EventLoop 上初始化（initialize → sendAll → 同步 drain）
        when(eventLoop.inEventLoop()).thenReturn(true);
        when(channel.isActive()).thenReturn(true);
        when(channel.isWritable()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(channelFuture);
    }

    @Test
    void sendAll_batchesMultipleItemsIntoOneDrain() throws Exception {
        // 每条数据编码为 10 字节，5 条共 50 字节 < maxFlushBytes=4096
        byte[] chunk = "0123456789".getBytes(StandardCharsets.UTF_8);
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write(chunk);
            return null;
        }).when(emitter).encode(any(), any());
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        List<Object> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            batch.add("item" + i);
        }
        sender.sendAll(batch);

        // 单次 drain：5 条合并为一次 writeAndFlush
        verify(channel, times(1)).writeAndFlush(httpContentCaptor.capture());
        DefaultHttpContent content = httpContentCaptor.getValue();
        ByteBuf written = content.content();
        assertEquals(50, written.readableBytes(), "5 条小数据必须合并进单次 flush");
        assertEquals("0123456789" + "0123456789" + "0123456789"
                + "0123456789" + "0123456789", written.toString(StandardCharsets.UTF_8));
    }

    @Test
    void sendAll_exceedingFlushBytes_flushesInMultipleChunks() throws Exception {
        // 每条 2000 字节，5 条 = 10000 字节 > maxFlushBytes=4096。
        // drain 写入后检查阈值：2000,4000,6000(≥4096 flush 一次) → 2000,4000(末尾 flush)，
        // 共 2 次 flush（逐条 send 会是 5 次小 flush）。
        byte[] chunk = new byte[2000];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) 'a';
        }
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write(chunk);
            return null;
        }).when(emitter).encode(any(), any());
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        List<Object> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            batch.add("item" + i);
        }
        sender.sendAll(batch);

        verify(channel, times(2)).writeAndFlush(any(DefaultHttpContent.class));
    }

    @Test
    void sendAll_earlyEncode_byteArrayBatch() throws Exception {
        // earlyEncode 路径：数据已是 byte[]，drain 直接拷贝，不调 encode
        EarlyEncodeNettyStreamSender sender = new EarlyEncodeNettyStreamSender(emitter, asyncWebRequest);

        byte[] item = "0123456789".getBytes(StandardCharsets.UTF_8);
        List<Object> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            batch.add(item);
        }
        sender.sendAll(batch);

        verify(emitter, never()).encode(any(), any());
        verify(channel, times(1)).writeAndFlush(httpContentCaptor.capture());
        assertEquals(50, httpContentCaptor.getValue().content().readableBytes());
    }

    @Test
    void sendAll_queuesAllBeforeDrain_queueSizeIsBatchSize() throws Exception {
        // 在 EventLoop 上（inEventLoop=true）sendAll 会同步 drain，drain 后队列清空。
        // 用一个非 EventLoop 的调度来观察"批量入队后才调度 drain"：
        // 改为 inEventLoop=false，让 scheduleDrain 走 eventLoop.execute，队列保留批量。
        when(eventLoop.inEventLoop()).thenReturn(false);
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("x".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(emitter).encode(any(), any());
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        List<Object> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            batch.add("item" + i);
        }
        sender.sendAll(batch);

        // drain 被调度但尚未执行 → 队列里应保留全部 5 条
        assertEquals(5, sender.queueSize(), "sendAll 必须先整批入队再调度 drain");
        verify(eventLoop, times(1)).execute(any(Runnable.class));
    }

    @Test
    void sendAll_emptyBatch_noDrain() throws Exception {
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        sender.sendAll(new ArrayList<>());

        verify(channel, never()).writeAndFlush(any(DefaultHttpContent.class));
    }

    @Test
    void sendAll_closedChannel_throws() throws Exception {
        when(channel.isActive()).thenReturn(false);
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);

        assertThrows(IOException.class, () -> sender.sendAll(java.util.Collections.singletonList("a")));
    }

    @Test
    void sendAll_completed_throws() throws Exception {
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);
        sender.complete(true, null);

        assertThrows(IOException.class, () -> sender.sendAll(java.util.Collections.singletonList("a")));
    }

    @Test
    void initialize_earlySendDataList_flushesViaSendAll() throws Exception {
        // 回归：StreamEmitter.initialize 对早发数据使用 sendAll，而非逐条 send
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("d".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(emitter).encode(any(), any());
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);
        StreamEmitter realEmitter = new SseEmitter();
        // 模拟 producer 线程在 sender 创建前就发送了数据（进 earlySendDataList）
        realEmitter.send("m1");
        realEmitter.send("m2");
        realEmitter.send("m3");

        realEmitter.initialize(sender);

        // 3 条早发数据在 initialize 中批量 flush → 单次 writeAndFlush
        verify(channel, times(1)).writeAndFlush(httpContentCaptor.capture());
        assertEquals("ddd", httpContentCaptor.getValue().content().toString(StandardCharsets.UTF_8));
    }
}
