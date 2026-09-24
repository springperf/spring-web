package io.springperf.web.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportUtils;

/**
 * 验证"响应未提交 buf 的生命周期绑定到请求"：{@link NettyServerHttpRequest#release()} 应级联释放其绑定的 {@link NettyServerHttpResponse} 未提交
 * buf，且对同步/异步路径统一， 不再依赖 {@code isAsyncRequest} 守卫。
 */
class NettyServerHttpRequestLifecycleTest {

    private WebContext webContext;
    private ChannelHandlerContext ctx;
    private NettyServerHttpResponse response;

    @BeforeEach
    void setUp() {
        webContext = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.getHttpTimeoutMillis()).thenReturn(60000L);
        when(props.getMaxInMemorySize()).thenReturn(8192);
        when(webContext.getProps()).thenReturn(props);

        ctx = mock(ChannelHandlerContext.class);
        ByteBufAllocator allocator = mock(ByteBufAllocator.class);
        // 每次分配返回一个真实（可计数）池外缓冲，便于观测 refCnt
        when(allocator.buffer(anyInt())).thenReturn(Unpooled.buffer(256));
        when(ctx.alloc()).thenReturn(allocator);
        // flush 路径需要 writeAndFlush 返回 ChannelFuture
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));

        response = new NettyServerHttpResponse(webContext, ctx, false);
    }

    private NettyServerHttpRequest newRequest() {
        FullHttpRequest msg = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/t",
                Unpooled.buffer(0));
        return new NettyServerHttpRequest(webContext, ctx, msg, "/t");
    }

    @Test
    void release_cascadesToResponseBuf() {
        NettyServerHttpRequest req = newRequest();
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
        NettyServerHttpRequest req = newRequest();
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
        NettyServerHttpRequest req = newRequest();
        assertDoesNotThrow(() -> {
            req.release();
        });
    }

    @Test
    void release_afterFlush_isSafeNoop() {
        // 已 flush（buf 已转移给 Netty 并置空，响应已提交）后 release 级联 release
        // 应为安全 no-op：已提交 buf 归 Netty 释放，不会 double-release；后续 getBuf 仍可正常使用。
        NettyServerHttpRequest req = newRequest();
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
}
