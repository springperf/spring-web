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
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
                new PublisherToStreamEmitterAdapter(emitter, sender, new ReactiveConfig(150, 50, -1), asyncWebRequest);

        adapter.onSubscribe(mock(Subscription.class));

        assertNotNull(emitter.getWriteCallbackHandler(), "订阅建立后写回调必须已注册");
    }

    @Test
    void asyncOnSubscribe_bindsWriteCallback_afterSubscribeReturns() throws Exception {
        // 回归：onSubscribe 异步投递的 Publisher（publishOn/subscribeOn 边界、第三方实现）。
        // 修复前 resolveReturnValue 在 subscribe() 返回后事后读取 emitter.getWriteCallbackHandler()，
        // 此时 onSubscribe 尚未执行 → 读到 null → addWriteCallbackHandler(null) → 写完成永不
        // 触发补充请求 → 流在 highWaterMark 后永久停滞。修复后 onSubscribe 内直接注册给
        // asyncWebRequest，订阅建立（无论同步/异步）后写回调立即可用。
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch subscribed = new CountDownLatch(1);
        try {
            SseEmitter emitter = new SseEmitter();
            DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);
            Subscription subscription = mock(Subscription.class);
            PublisherToStreamEmitterAdapter adapter =
                    new PublisherToStreamEmitterAdapter(emitter, sender, new ReactiveConfig(150, 50, -1), asyncWebRequest);

            new AsyncSubscribePublisher(executor, subscription, subscribed).subscribe(adapter);
            // subscribe() 返回时 onSubscribe 尚未投递（异步），模拟真实异步 Publisher
            assertTrue(subscribed.await(2, TimeUnit.SECONDS), "异步 onSubscribe 应已投递");

            // 写完成回调：onSubscribe 内已注册给 asyncWebRequest →
            // writeStreamSuccessCallback → tryRequest → subscription.request(补充)
            asyncWebRequest.writeStreamSuccessCallback();
            // 初始 request(150) + 写完成补充 request(150 - queueSize=0) = request(150)
            verify(subscription, times(2)).request(anyLong());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void writeComplete_triggersBackpressureRequest_throughAdapter() throws Exception {
        SseEmitter emitter = new SseEmitter();
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);
        PublisherToStreamEmitterAdapter adapter =
                new PublisherToStreamEmitterAdapter(emitter, sender, new ReactiveConfig(150, 50, -1), asyncWebRequest);
        Subscription subscription = mock(Subscription.class);

        adapter.onSubscribe(subscription); // 初始 request(150) + 注册写回调（onSubscribe 内已同步给 asyncWebRequest）
        // 发送一条 → drain 直写 channel 现在挂写监听器（修复点②）→ 写完成 → 写回调 → tryRequest
        adapter.onNext("data");

        // 初始 request(150) + 写完成补充 request(150 - queueSize=0) = request(150)
        verify(subscription, times(2)).request(anyLong());
    }

    @Test
    void syncPublisher_largeCount_noStackOverflow() throws Exception {
        // 回归：同步 Publisher（如 Flux.range / fromIterable）在快速 socket 上写完成立即回调。
        // 修复前 scheduleDrain 的 inEventLoop 分支无条件 drain()：drain → flushContent 写完成
        // → writeStreamSuccessCallback → tryRequest → subscription.request → 同步 onNext
        // → sender.send → scheduleDrain → 递归 drain()，深度随同步元素数增长 → StackOverflowError。
        // 修复后 wip 计数打断重入：drain 执行中 send 只递增 wip，由当前 drain 的 missed 循环消化。
        SseEmitter emitter = new SseEmitter();
        DefaultNettyStreamSender sender = new DefaultNettyStreamSender(emitter, asyncWebRequest);
        ReactiveConfig config = new ReactiveConfig(50_000, 1, -1);
        PublisherToStreamEmitterAdapter adapter =
                new PublisherToStreamEmitterAdapter(emitter, sender, config, asyncWebRequest);

        assertDoesNotThrow(() -> {
            // adapter 本身是 Subscriber，直接订阅同步 Publisher；
            // 写回调由 onSubscribe 内注册给 asyncWebRequest（无需事后 bind）
            new SyncRangePublisher(200_000).subscribe(adapter);
            StreamEmitterUtil.initializeWithStreamSender(emitter, sender);
        });
        // 全部同步元素已写出（outbound 至少含一个 HttpContent 帧）
        assertTrue(channel.outboundMessages().size() > 0, "同步元素应全部写出");
    }

    /**
     * 异步 Publisher：onSubscribe 在独立线程投递（模拟 publishOn/subscribeOn 边界的异步投递），
     * subscribe() 返回时 onSubscribe 尚未执行。
     */
    static class AsyncSubscribePublisher implements Publisher<Object> {
        private final ExecutorService executor;
        private final Subscription outer;
        private final CountDownLatch subscribed;

        AsyncSubscribePublisher(ExecutorService executor, Subscription outer, CountDownLatch subscribed) {
            this.executor = executor;
            this.outer = outer;
            this.subscribed = subscribed;
        }

        @Override
        public void subscribe(Subscriber<? super Object> subscriber) {
            executor.execute(() -> {
                subscriber.onSubscribe(outer);
                subscribed.countDown();
            });
        }
    }

    /**
     * 同步 Publisher：request(n) 在调用线程同步回调 n 个 onNext（模拟 Flux.range 的同步投递），
     * 元素发完后 onComplete。count 远大于 highWaterMark，使每次写完成补充请求都会重入。
     */
    static class SyncRangePublisher implements org.reactivestreams.Publisher<Object> {
        private final int count;

        SyncRangePublisher(int count) {
            this.count = count;
        }

        @Override
        public void subscribe(org.reactivestreams.Subscriber<? super Object> subscriber) {
            subscriber.onSubscribe(new Subscription() {
                private int emitted;

                @Override
                public void request(long n) {
                    while (emitted < count && n > 0) {
                        subscriber.onNext("v" + emitted);
                        emitted++;
                        n--;
                    }
                    if (emitted == count) {
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                }
            });
        }
    }
}
