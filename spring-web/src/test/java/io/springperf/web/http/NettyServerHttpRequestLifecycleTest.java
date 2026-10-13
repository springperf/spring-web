package io.springperf.web.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.IllegalReferenceCountException;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 请求对象生命周期：refCnt 的 acquire/release 契约，以及「响应未提交 buf 的生命周期绑定到请求」——
 * {@link NettyServerHttpRequest#release()} 应级联释放其绑定的 {@link NettyServerHttpResponse} 未提交 buf，
 * 且对同步/异步路径统一，不再依赖 {@code isAsyncRequest} 守卫。
 *
 * <p>本文件为 master（refCnt 契约 + 级联释放）与本地（refCnt 契约 + Wrapper 委托 + 并发）两个用例集的合并版：
 * master 的 4 条级联释放用例依赖 B4 已落地的 {@code release()} 级联主代码（见同步清单 §15.3）。</p>
 */
@ExtendWith(MockitoExtension.class)
class NettyServerHttpRequestLifecycleTest {

    @Mock
    private WebContext webContext;
    @Mock
    private ChannelHandlerContext ctx;
    @Mock
    private ApplicationProperties props;

    private NettyServerHttpResponse response;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(props.getInt(anyString())).thenReturn(4096);
        // 级联释放用例需要：超时取值、body 上限（实例级）、可计数的真实缓冲、flush 路径的 future
        lenient().when(props.getHttpTimeoutMillis()).thenReturn(60000L);
        lenient().when(props.getMaxInMemorySize()).thenReturn(8192);
        ByteBufAllocator allocator = mock(ByteBufAllocator.class);
        // 每次分配返回全新的真实（可计数）池外缓冲，便于观测 refCnt
        lenient().when(allocator.buffer(anyInt())).thenAnswer(inv -> Unpooled.buffer(256));
        lenient().when(ctx.alloc()).thenReturn(allocator);
        lenient().when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));

        response = new NettyServerHttpResponse(webContext, ctx, false);
    }

    // ---------------------------------------------------------------
    // 响应未提交 buf 的生命周期绑定到请求（master）
    // ---------------------------------------------------------------

    @Test
    void release_cascadesToResponseBuf() {
        NettyServerHttpRequest req = newBareRequest();
        req.setResponse(response);

        ByteBuf buf = response.getBuf();
        assertEquals(1, buf.refCnt());
        try {
            response.getBody().write("data".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            fail("getBody().write should not throw", e);
        }
        assertFalse(response.isCommitted(), "写 body 但未 flush 时响应不应已提交");

        req.release();

        assertEquals(0, buf.refCnt(), "req.release() 应级联释放绑定的响应未提交 buf（生命周期绑定）");
    }

    @Test
    void release_cascadesOnlyOnLastRelease_evenWhenAsyncStarted() {
        NettyServerHttpRequest req = newBareRequest();
        req.setResponse(response);

        ByteBuf buf = response.getBuf();
        try {
            response.getBody().write("data".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            fail("getBody().write should not throw", e);
        }

        // 异步挂起：startAsync 使异步持有者 acquire 一次（refCnt 1 → 2）
        io.springperf.web.core.async.PerfAsyncWebRequest async = AsyncSupportUtils.getAsyncWebRequest(req, response);
        async.startAsyncProcessing();
        assertTrue(AsyncSupportUtils.isAsyncRequest(req), "异步应已启动");

        // 非最后一次 release（如 EventLoop 提交业务池后那次）：不得级联——
        // 业务线程可能仍在写响应缓冲，早释放会把正在被写的 buf 回收/清空
        req.release();
        assertEquals(1, buf.refCnt(), "非最后一次 release 不得级联释放未提交 buf");

        // 响应写终结：异步持有者退场 → 这才是最后一次 release → 此时才级联
        async.completeSuccessCallback();
        assertEquals(0, buf.refCnt(), "最后一次 release 应级联释放未提交 buf");
    }

    @Test
    void release_withoutBoundResponse_isNoop() {
        // 未绑定响应（如仅构造 request 做解析测试）时 release 不应抛异常
        NettyServerHttpRequest req = newBareRequest();
        assertDoesNotThrow(() -> {
            req.release();
        });
    }

    @Test
    void release_afterFlush_isSafeNoop() {
        // 已 flush（buf 已转移给 Netty 并置空，响应已提交）后 release 级联 release
        // 应为安全 no-op：已提交 buf 归 Netty 释放，不会 double-release；后续 getBuf 仍可正常使用。
        NettyServerHttpRequest req = newBareRequest();
        req.setResponse(response);

        try {
            response.getBody().write("x".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            fail("getBody().write should not throw", e);
        }
        assertDoesNotThrow(() -> response.flush());

        assertDoesNotThrow(() -> {
            req.release();
        });

        // 提交后再次 getBuf 仍能分配出全新（未被误释放）的缓冲
        ByteBuf afterFlush = response.getBuf();
        assertEquals(1, afterFlush.refCnt(), "flush + release 后重新分配的 buf 不应被误释放");
    }

    // ---------------------------------------------------------------
    // 核心：acquire → release，refCnt 正确变化
    // ---------------------------------------------------------------

    @Test
    void constructor_doesNotRetain() {
        FullHttpRequest nativeRequest = newRequest();
        assertEquals(1, nativeRequest.refCnt());

        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        // 构造不再 retain，由调用方（NettyHttpHandler）管理
        assertEquals(1, nativeRequest.refCnt());
    }

    @Test
    void acquire_incrementsRefCnt() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        assertEquals(1, nativeRequest.refCnt());

        req.acquire();

        assertEquals(2, nativeRequest.refCnt());
        nativeRequest.release(); // 平衡 acquire
    }

    @Test
    void release_decrementsRefCnt() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        req.acquire();
        assertEquals(2, nativeRequest.refCnt());

        req.release();

        assertEquals(1, nativeRequest.refCnt());
    }

    @Test
    void release_returnsTrue_whenFreed() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        // 直接 release（同步路径，Netty 的 refcnt 唯一持有者）
        assertTrue(req.release());
        assertEquals(0, nativeRequest.refCnt());
    }

    @Test
    void release_returnsFalse_whenNotFreed() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        req.acquire();
        assertEquals(2, nativeRequest.refCnt());

        // release 一次，refcnt 降到 1 未到 0 → false
        assertFalse(req.release());
        assertEquals(1, nativeRequest.refCnt());
        nativeRequest.release(); // 清理
    }

    // ---------------------------------------------------------------
    // 模拟异步/线程池路径：acquire → execute → release
    // ---------------------------------------------------------------

    @Test
    void lifecycle_survivesAsyncDispatch() throws Exception {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        assertEquals(1, nativeRequest.refCnt());

        // 模拟异步路径：acquire 后业务线程池中处理
        req.acquire();
        assertEquals(2, nativeRequest.refCnt());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        executor.execute(() -> {
            try {
                req.getUriStr();
                req.release();
            } catch (Throwable e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });

        latch.await();
        executor.shutdown();

        assertNull(error.get());
        assertEquals(1, nativeRequest.refCnt(), "release 后应回到 Netty 持有的 refcnt");
        nativeRequest.release(); // 清理 Netty 的 ref
    }

    @Test
    void lifecycle_acquireReleasePair_returnsToInitialRefCnt() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        req.acquire();
        req.acquire();
        assertEquals(3, nativeRequest.refCnt());

        req.release();
        req.release();
        assertEquals(1, nativeRequest.refCnt());
    }

    // ---------------------------------------------------------------
    // WebServerHttpRequestWrapper 委托
    // ---------------------------------------------------------------

    @Test
    void wrapper_delegatesAcquire() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        WebServerHttpRequestWrapper wrapper = new WebServerHttpRequestWrapper(req);

        wrapper.acquire();

        assertEquals(2, nativeRequest.refCnt());
        nativeRequest.release();
    }

    @Test
    void wrapper_delegatesRelease() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        WebServerHttpRequestWrapper wrapper = new WebServerHttpRequestWrapper(req);
        wrapper.acquire();
        assertEquals(2, nativeRequest.refCnt());

        wrapper.release();

        assertEquals(1, nativeRequest.refCnt());
    }

    // ---------------------------------------------------------------
    // 边缘场景：双释放、释放后 acquire
    // ---------------------------------------------------------------

    @Test
    void doubleRelease_throwsIllegalReferenceCountException() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        // 第一次 release: refCnt 1→0, 返回 true
        assertTrue(req.release());
        assertEquals(0, nativeRequest.refCnt());
        // 第二次 release: refCnt=0 应抛出 IllegalReferenceCountException
        assertThrows(IllegalReferenceCountException.class, req::release);
    }

    @Test
    void acquireAfterRelease_throwsIllegalReferenceCountException() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        req.release();
        assertEquals(0, nativeRequest.refCnt());
        assertThrows(IllegalReferenceCountException.class, req::acquire);
    }

    @Test
    void partialRelease_doesNotFree() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        req.acquire();
        int before = nativeRequest.refCnt();
        // release 一次但未全部释放
        req.release();
        assertEquals(before - 1, nativeRequest.refCnt());
    }

    // ---------------------------------------------------------------
    // 并发 acquire/release
    // ---------------------------------------------------------------

    @Test
    void concurrentAcquireRelease_maintainsCorrectRefCnt() throws Exception {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        int threadCount = 4;
        int opsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        req.acquire();
                    }
                    for (int j = 0; j < opsPerThread; j++) {
                        req.release();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(1, nativeRequest.refCnt(), "并发 acquire/release 后应回到初始 refCnt");
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    private static FullHttpRequest newRequest() {
        ByteBuf content = Unpooled.buffer();
        content.writeByte(1);
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test", content);
    }

    /** 级联释放用例用：直接给出已构造的请求对象（对应 master 的 {@code newRequest()}）。 */
    private NettyServerHttpRequest newBareRequest() {
        FullHttpRequest msg = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/t",
                Unpooled.buffer(0));
        return new NettyServerHttpRequest(webContext, ctx, msg, "/t");
    }
}
