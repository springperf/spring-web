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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归：{@link AbstractNettyStreamSender#scheduleDrain()} 在 drain 执行中重入时的递归栈溢出。
 * <p>
 * 真实链路（同步 Publisher）：drain → flushContent 写完成（快速 socket 立即回调）
 * → writeStreamSuccessCallback → tryRequest → subscription.request → 同步 onNext
 * → sender.send → scheduleDrain → 若 inEventLoop 分支无条件 drain() 则逐元素嵌套递归，
 * {@code Flux.range(1, N)} 长流 StackOverflowError。
 * <p>
 * 本测试用最小 drain 子类直接模拟"drain 执行中重入 scheduleDrain"，精确验证修复：
 * wip 计数打断重入（修复前 depth 层递归，修复后仅执行一次 drain）。
 */
class AbstractNettyStreamSenderDrainReentryTest {

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

    @Test
    void drainReentry_duringDrain_executesOnce() {
        ReentrySender sender = new ReentrySender(new SseEmitter(), asyncWebRequest);
        // drain 内重入 scheduleDrain：修复前 inEventLoop 分支无条件 drain()，
        // 100000 层递归 StackOverflowError；修复后 wip 计数打断，仅执行一次。
        assertDoesNotThrow(sender::scheduleDrain);
        assertEquals(1, sender.drainCount, "drain 执行中的重入必须被 wip 计数打断");
    }

    /**
     * 最小发送器：drain 内直接重入 scheduleDrain，模拟 flushContent 写回调（同步 Publisher）
     * 在 drain 执行中触发的 send → scheduleDrain 重入。
     */
    static class ReentrySender extends AbstractNettyStreamSender {
        int drainCount;

        ReentrySender(StreamEmitter emitter, PerfAsyncWebRequest asyncWebRequest) {
            super(emitter, asyncWebRequest);
        }

        @Override
        public void send(Object data) throws java.io.IOException {
            // 本测试只验证 drain 重入递归，不发送实际数据
        }

        @Override
        protected void drain() {
            drainCount++;
            if (drainCount < 100_000) {
                scheduleDrain();
            }
        }

        @Override
        protected void afterDrain() {
            // 不写 LastHttpContent，仅验证递归行为
        }
    }
}
