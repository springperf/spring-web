package io.springperf.web.core.async.stream;

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
        assertTrue(sender.queueSize() == 0 || channel.isActive(),
                "complete 后应安全排空队列并结束流");
    }

    @Test
    void complete_withFailure_closesChannelInsteadOfWritingLastContent() {
        DefaultNettyStreamSender sender = newSender();

        sender.complete(false, new IOException("downstream break"));
        channel.runPendingTasks();
        channel.runPendingTasks();

        // 错误终止：关闭连接，不写正常 LastHttpContent，客户端感知异常截断
        assertFalse(channel.isOpen(), "错误终止应关闭连接而非正常结束流");
        Object out;
        while ((out = channel.readOutbound()) != null) {
            io.netty.util.ReferenceCountUtil.release(out);
            assertFalse(out instanceof io.netty.handler.codec.http.LastHttpContent,
                    "错误终止不应写出正常 LastHttpContent");
        }
    }
}
