package io.springperf.web.core.async.stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportUtils;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.NettyServerHttpResponse;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 补充 {@link AbstractNettyStreamSender} 边界：preSendCheck 快速失败、
 * sendAll 批量入队/空集合、complete 关闭通道。
 */
class AbstractNettyStreamSenderDetailsTest {

    private EmbeddedChannel channel;
    private ChannelHandlerContext ctx;
    private WebServerHttpRequest req;
    private RequestContext rc;
    private Map<RequestAttribute<?>, Object> attrs;
    private NettyServerHttpResponse resp;
    private PerfAsyncWebRequest asyncWebRequest;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        channel = new EmbeddedChannel();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
        });
        ctx = channel.pipeline().firstContext();
        req = mock(WebServerHttpRequest.class);
        rc = mock(RequestContext.class);
        attrs = new HashMap<>();
        when(req.getRequestContext()).thenReturn(rc);
        when(rc.getAttribute(any(RequestAttribute.class)))
                .thenAnswer(inv -> attrs.get((RequestAttribute<?>) inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put((RequestAttribute<?>) inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(rc).setAttribute(any(RequestAttribute.class), any());

        resp = new NettyServerHttpResponse(mock(WebContext.class), ctx, true);
        asyncWebRequest = AsyncSupportUtils.getAsyncWebRequest(req, resp);
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    private DefaultNettyStreamSender newSender() {
        return new DefaultNettyStreamSender(new SseEmitter(), asyncWebRequest);
    }

    @Test
    void send_whenChannelInactive_throwsIOException() {
        DefaultNettyStreamSender sender = newSender();
        channel.close().awaitUninterruptibly();

        assertThrows(IOException.class, () -> sender.send("x"),
                "通道关闭后发送应快速失败");
    }

    /**
     * 流在连接断开后收尾：这条分支不写任何帧（没有写完成回调），{@code channel.close()} 也不会再触发一次 {@code channelInactive}（连接本来就断着）——异步持有者必须由发送器显式终结。
     * <p>
     * 修复前这里什么都不做：持有者与入站缓冲变成没有外部根的环，在飞计数停在 1，缓冲等 GC 才被 Netty 判为泄漏。
     * </p>
     */
    @Test
    void complete_whenChannelInactive_completesAsyncWithoutWriting() {
        DefaultNettyStreamSender sender = newSender();
        asyncWebRequest.startAsync();
        channel.close().awaitUninterruptibly();

        sender.complete(false, null);

        assertTrue(asyncWebRequest.isAsyncComplete(), "无对端可写时流收尾必须终结异步持有者");
        verify(req, times(1)).release();
    }

    /** 错误终止同理：它也只 close 连接，连接已断时兜底钩子没有第二次机会。 */
    @Test
    void completeWithError_whenChannelInactive_stillCompletesAsync() {
        DefaultNettyStreamSender sender = newSender();
        asyncWebRequest.startAsync();
        channel.close().awaitUninterruptibly();

        sender.complete(false, new IOException("boom"));

        assertTrue(asyncWebRequest.isAsyncComplete());
        verify(req, times(1)).release();
    }

    @Test
    void send_whenCompleted_throwsIOException() throws Exception {
        DefaultNettyStreamSender sender = newSender();
        sender.complete(false, null);

        assertThrows(IOException.class, () -> sender.send("x"),
                "流完成后发送应快速失败");
    }

    @Test
    void sendAll_emptyCollection_noOp() throws Exception {
        DefaultNettyStreamSender sender = newSender();
        sender.sendAll(Collections.emptyList());
        assertEquals(0, sender.queueSize());
    }

    @Test
    void sendAll_multipleItems_enqueuedAndFlushed() throws Exception {
        DefaultNettyStreamSender sender = newSender();
        sender.sendAll(Arrays.asList("a", "b", "c"));
        // 测试线程即 EventLoop：drain 同步执行，队列被消费并写出
        assertEquals(0, sender.queueSize(), "批量入队后 drain 应同步消费队列");

        int frames = 0;
        Object out;
        while ((out = channel.readOutbound()) != null) {
            io.netty.util.ReferenceCountUtil.release(out);
            frames++;
        }
        assertTrue(frames >= 1, "批量数据应写出到通道 outbound");
    }

    @Test
    void complete_withCloseChannelOnComplete_terminates() {
        DefaultNettyStreamSender sender = newSender();
        assertDoesNotThrow(() -> sender.complete(true, null));
        channel.runPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isActive(), "closeChannelOnComplete=true 应在收尾后关闭通道");

        boolean lastContentSeen = false;
        Object out;
        while ((out = channel.readOutbound()) != null) {
            if (out instanceof io.netty.handler.codec.http.LastHttpContent) {
                lastContentSeen = true;
            }
            io.netty.util.ReferenceCountUtil.release(out);
        }
        assertTrue(lastContentSeen, "正常完成应写出 LastHttpContent 终止块");
    }

    @Test
    void completeWithError_closesChannelWithoutLastHttpContent() throws Exception {
        DefaultNettyStreamSender sender = newSender();
        // 先入队一段数据：错误终止必须在「已有待写内容」的前提下仍不写正常 LastHttpContent
        sender.send("data");
        sender.complete(false, new IllegalStateException("boom"));
        channel.runPendingTasks();
        channel.runPendingTasks();

        // 错误终止：关闭连接，不写正常 LastHttpContent，客户端感知异常截断
        assertFalse(channel.isActive(), "错误终止应关闭通道而非正常结束流");
        Object out;
        boolean lastContentSeen = false;
        while ((out = channel.readOutbound()) != null) {
            if (out instanceof io.netty.handler.codec.http.LastHttpContent) {
                lastContentSeen = true;
            }
            io.netty.util.ReferenceCountUtil.release(out);
        }
        assertFalse(lastContentSeen, "错误终止不应发送正常 LastHttpContent");
    }
}
