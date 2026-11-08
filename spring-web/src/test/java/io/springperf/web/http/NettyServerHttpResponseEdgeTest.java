package io.springperf.web.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.springperf.web.context.WebContext;
import io.springperf.web.server.ResponseLimitConfig;

/** HTTP/1.0 close-delimited 退化、响应头限制与写后交换结束的缓冲分配。 */
@ExtendWith(MockitoExtension.class)
class NettyServerHttpResponseEdgeTest {

    @Mock
    WebContext webContext;
    @Mock
    ChannelHandlerContext ctx;
    @Mock
    EventLoop eventLoop;
    @Mock
    ByteBufAllocator allocator;
    @Mock
    ChannelFuture channelFuture;

    @BeforeEach
    void setUp() {
        lenient().when(ctx.executor()).thenReturn(eventLoop);
        lenient().when(ctx.alloc()).thenReturn(allocator);
        lenient().when(allocator.buffer(256)).thenAnswer(invocation -> Unpooled.buffer(256));
        lenient().when(ctx.attr(any())).thenReturn(mock(io.netty.util.Attribute.class));
        lenient().when(ctx.write(any())).thenReturn(channelFuture);
        lenient().when(ctx.writeAndFlush(any())).thenReturn(channelFuture);
        lenient().when(channelFuture.addListener(any())).thenReturn(channelFuture);
    }

    private NettyServerHttpResponse response(boolean keepAlive, int headerLimit) {
        return new NettyServerHttpResponse(webContext, ctx, keepAlive, new ResponseLimitConfig(-1, headerLimit), 0L);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = NettyServerHttpResponse.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    @Test
    void http10WithPendingBodyWritesNonFullHeadersAndFirstContent() throws Exception {
        NettyServerHttpResponse resp = response(true, 0);
        resp.markHttp10();
        resp.getBody().write("abc".getBytes(StandardCharsets.UTF_8));

        resp.flushChunked();

        ArgumentCaptor<Object> headers = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> flushed = ArgumentCaptor.forClass(Object.class);
        verify(ctx).write(headers.capture());
        verify(ctx).writeAndFlush(flushed.capture());
        HttpResponse head = (HttpResponse) headers.getValue();
        assertFalse(head instanceof FullHttpResponse, "HTTP/1.0 不能用 FullHttpResponse 提前结束交换");
        assertEquals("close", head.headers().get(HttpHeaderNames.CONNECTION));
        assertNull(head.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
        assertNull(head.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        ByteBuf content = ((DefaultHttpContent) flushed.getValue()).content();
        assertEquals("abc", content.toString(StandardCharsets.UTF_8));
        content.release();
    }

    @Test
    void http10WithoutPendingBodyWritesHeadersOnly() throws Exception {
        NettyServerHttpResponse resp = response(true, 0);
        resp.markHttp10();

        resp.flushChunked();

        ArgumentCaptor<Object> flushed = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(flushed.capture());
        HttpResponse head = (HttpResponse) flushed.getValue();
        assertFalse(head instanceof FullHttpResponse);
        assertEquals("close", head.headers().get(HttpHeaderNames.CONNECTION));
        verify(ctx, never()).write(any());
    }

    @Test
    void http10EndStreamClosesConnection() throws Exception {
        NettyServerHttpResponse resp = response(true, 0);
        resp.markHttp10();
        resp.flushChunked();
        clearInvocations(ctx, channelFuture);

        resp.endStream();

        verify(ctx).writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        verify(channelFuture).addListener(eq(ChannelFutureListener.CLOSE));
    }

    @Test
    void responseHeaderLimitZeroDisablesCheck() throws Exception {
        NettyServerHttpResponse resp = response(true, 0);
        resp.getHeaders().add("X-Long", "a-very-long-header-value");

        assertFalse((Boolean) invoke(resp, "responseHeadersExceedLimit", new Class<?>[0]));
    }

    @Test
    void responseHeaderLimitDetectsOversizedHeaders() throws Exception {
        NettyServerHttpResponse resp = response(true, 4);
        resp.getHeaders().add("X-Long", "a-very-long-header-value");

        assertTrue((Boolean) invoke(resp, "responseHeadersExceedLimit", new Class<?>[0]));
    }

    @Test
    void responseHeaderLimitCountsOnlyFirstValueOfDuplicateNames() throws Exception {
        NettyServerHttpResponse resp = response(true, 12);
        resp.getHeaders().add("X", "1234");
        resp.getHeaders().add("X", "1234");

        assertFalse((Boolean) invoke(resp, "responseHeadersExceedLimit", new Class<?>[0]), "同名多值只应计首个，否则会误判超限");
    }

    @Test
    void headerTooLargeWritesMinimal500AndHonorsKeepAlive() throws Exception {
        for (boolean keepAlive : new boolean[] { false, true }) {
            clearInvocations(ctx, channelFuture);
            NettyServerHttpResponse resp = response(keepAlive, 1);

            invoke(resp, "writeHeaderTooLarge", new Class<?>[0]);

            ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
            verify(ctx).writeAndFlush(written.capture());
            FullHttpResponse error = (FullHttpResponse) written.getValue();
            assertEquals(500, error.status().code());
            assertEquals("0", error.headers().get(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals(keepAlive ? "keep-alive" : "close", error.headers().get(HttpHeaderNames.CONNECTION));
            verify(channelFuture, times(keepAlive ? 0 : 1)).addListener(eq(ChannelFutureListener.CLOSE));
            error.release();
        }
    }

    @Test
    void getBufAfterChannelInactiveUsesUnpooledBuffer() {
        Channel channel = mock(Channel.class);
        when(ctx.channel()).thenReturn(channel);
        when(channel.isActive()).thenReturn(false);
        NettyServerHttpResponse resp = response(true, 0);

        ByteBuf buf = resp.getBuf();

        assertTrue(buf.getClass().getName().contains("Unpooled"), "交换结束后不能分配池化缓冲，否则泄漏");
        verify(allocator, never()).buffer(256);
    }
}
