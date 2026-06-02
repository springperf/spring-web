package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NettyServerHttpRequestLifecycleTest {

    @Mock
    private WebContext webContext;
    @Mock
    private ChannelHandlerContext ctx;

    @BeforeEach
    void setUp() {
    }

    // ---------------------------------------------------------------
    // 核心：构造 retain → complete release，refCnt 正确变化
    // ---------------------------------------------------------------

    @Test
    void constructor_retainsNativeRequest() {
        FullHttpRequest nativeRequest = newRequest();
        assertEquals(1, nativeRequest.refCnt());

        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        assertEquals(2, nativeRequest.refCnt());
        assertFalse(req.isCompleted());
        nativeRequest.release();
    }

    @Test
    void complete_releasesRetainedRef() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        assertEquals(2, nativeRequest.refCnt());

        req.complete();

        assertTrue(req.isCompleted());
        assertEquals(1, nativeRequest.refCnt());
        nativeRequest.release();
    }

    @Test
    void complete_isIdempotent() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        assertEquals(2, nativeRequest.refCnt());

        req.complete();
        assertEquals(1, nativeRequest.refCnt());

        req.complete();
        assertEquals(1, nativeRequest.refCnt());

        nativeRequest.release();
    }

    // ---------------------------------------------------------------
    // 模拟异步/线程池路径：SimpleChannelInboundHandler release 后调用 complete
    // ---------------------------------------------------------------

    @Test
    void lifecycle_survivesSimulatedChannelRead0() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        // 模拟 SimpleChannelInboundHandler 自动 release
        nativeRequest.release();
        assertEquals(1, nativeRequest.refCnt());

        req.complete();
        assertEquals(0, nativeRequest.refCnt());
    }

    @Test
    void lifecycle_survivesBizPoolDispatch() throws Exception {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        nativeRequest.release();
        assertEquals(1, nativeRequest.refCnt());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        executor.execute(() -> {
            try {
                req.getUriStr();
                req.complete();
            } catch (Throwable e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });

        latch.await();
        executor.shutdown();

        assertNull(error.get());
        assertEquals(0, nativeRequest.refCnt(), "ByteBuf complete 后应释放");
    }

    // ---------------------------------------------------------------
    // WebServerHttpRequestWrapper 委托
    // ---------------------------------------------------------------

    @Test
    void wrapper_delegatesComplete() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        WebServerHttpRequestWrapper wrapper = new WebServerHttpRequestWrapper(req);
        assertFalse(wrapper.isCompleted());

        wrapper.complete();

        assertTrue(wrapper.isCompleted());
        assertTrue(req.isCompleted());
        assertEquals(1, nativeRequest.refCnt());
        nativeRequest.release();
    }

    @Test
    void wrapper_delegatesIsCompleted() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        WebServerHttpRequestWrapper wrapper = new WebServerHttpRequestWrapper(req);
        assertFalse(wrapper.isCompleted());

        req.complete();
        assertTrue(wrapper.isCompleted());

        nativeRequest.release();
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
