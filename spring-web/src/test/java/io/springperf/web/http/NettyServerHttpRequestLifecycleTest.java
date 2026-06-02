package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class NettyServerHttpRequestLifecycleTest {

    @Mock
    private WebContext webContext;
    @Mock
    private ChannelHandlerContext ctx;

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
    // 辅助
    // ---------------------------------------------------------------

    private static FullHttpRequest newRequest() {
        ByteBuf content = Unpooled.buffer();
        content.writeByte(1);
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test", content);
    }
}
