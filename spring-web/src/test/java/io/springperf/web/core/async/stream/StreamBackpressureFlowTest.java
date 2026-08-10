package io.springperf.web.core.async.stream;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportUtils;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.core.async.reactive.PublisherToStreamEmitterAdapter;
import io.springperf.web.core.async.reactive.ReactiveConfig;
import io.springperf.web.http.NettyServerHttpResponse;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归：{@link PublisherToStreamEmitterAdapter} 背压补充请求链路。
 * <p>
 * 修复前两处缺陷叠加导致遵守背压的 cold Publisher 在 highWaterMark 条后流永久停滞：
 * <ol>
 *   <li>时序错误——写回调在 subscribe 之后才注册，而 asyncWebRequest 在 subscribe 之前就
 *       绑定了 emitter 的（null）写回调；</li>
 *   <li>机制缺陷——sender drain 直写 channel 不挂写监听器，写完成不触发写回调。</li>
 * </ol>
 * 修复后：{@code drain 写完成 → writeStreamSuccessCallback → tryRequest → subscription.request(增量)}。
 */
class StreamBackpressureFlowTest {

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
        // 空 pipeline 无 context（此 Netty 变体 EmbeddedChannel 需先 addLast 才有 ctx）
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
        // AsyncSupportUtils 通过 requestContext 属性缓存 asyncWebRequest，两次取回同一实例
        asyncWebRequest = AsyncSupportUtils.getAsyncWebRequest(req, resp);
        asyncWebRequest.startAsync(); // 注册 WriteRespEventListener → 写完成才能触发写回调
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Test
    void writeCallbackHandler_isNull_beforeSubscribe() {
        // 时序缺陷根源：订阅建立前 emitter 的写回调未注册（修复前 asyncWebRequest 绑定到该 null）
        SseEmitter emitter = new SseEmitter();
        assertNull(emitter.getWriteCallbackHandler());
    }

    @Test
    void writeCallbackHandler_registered_afterSubscribe() throws Exception {
        SseEmitter emitter = new SseEmitter();
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);
        PublisherToStreamEmitterAdapter adapter =
                new PublisherToStreamEmitterAdapter(emitter, sender, new ReactiveConfig(150, 50, -1));

        adapter.onSubscribe(mock(Subscription.class));

        assertNotNull(emitter.getWriteCallbackHandler(), "订阅建立后写回调必须已注册");
    }

    @Test
    void writeComplete_triggersBackpressureRequest_throughAdapter() throws Exception {
        SseEmitter emitter = new SseEmitter();
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);
        PublisherToStreamEmitterAdapter adapter =
                new PublisherToStreamEmitterAdapter(emitter, sender, new ReactiveConfig(150, 50, -1));
        Subscription subscription = mock(Subscription.class);

        adapter.onSubscribe(subscription); // 初始 request(150) + 注册写回调
        // 订阅建立后把非 null 写回调同步给 asyncWebRequest（修复点①）
        StreamEmitterUtil.bindWriteCallbackHandler(emitter, req, resp);

        // 发送一条 → drain 直写 channel 现在挂写监听器（修复点②）→ 写完成 → 写回调 → tryRequest
        adapter.onNext("data");

        // 初始 request(150) + 写完成补充 request(150 - queueSize=0) = request(150)
        verify(subscription, times(2)).request(anyLong());
    }
}
