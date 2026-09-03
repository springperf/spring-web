package io.springperf.web.server;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NettyHttpServerDetailsTest {

    @Test
    void getOrder_returnsLowestPrecedence() {
        NettyHttpServer server = new NettyHttpServer(mock(WebContext.class));
        assertEquals(Ordered.LOWEST_PRECEDENCE, server.getOrder());
    }

    @Test
    void getActualPort_beforeStart_returnsZero() {
        NettyHttpServer server = new NettyHttpServer(mock(WebContext.class));
        assertEquals(0, server.getActualPort());
    }

    @Test
    void getWorkerGroup_beforeStart_returnsNull() {
        NettyHttpServer server = new NettyHttpServer(mock(WebContext.class));
        assertNull(server.getWorkerGroup());
    }

    @Test
    void getActiveConnectionCount_beforeStart_zero() {
        NettyHttpServer server = new NettyHttpServer(mock(WebContext.class));
        assertEquals(0, server.getActiveConnectionCount());
    }

    @Test
    void sslExceptionHandler_notSslRecord_closesChannel() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        NettyHttpServer.SslExceptionHandler handler = NettyHttpServer.SslExceptionHandler.INSTANCE;
        handler.exceptionCaught(ctx, new NotSslRecordException());
        verify(ctx).close();
        verify(ctx, never()).fireExceptionCaught(any());
    }

    @Test
    void sslExceptionHandler_decoderWrappingNotSslRecord_closesChannel() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        NettyHttpServer.SslExceptionHandler handler = NettyHttpServer.SslExceptionHandler.INSTANCE;
        handler.exceptionCaught(ctx, new DecoderException(new NotSslRecordException()));
        verify(ctx).close();
    }

    @Test
    void sslExceptionHandler_otherException_forwards() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        NettyHttpServer.SslExceptionHandler handler = NettyHttpServer.SslExceptionHandler.INSTANCE;
        RuntimeException ex = new RuntimeException("boom");
        handler.exceptionCaught(ctx, ex);
        verify(ctx, never()).close();
        verify(ctx).fireExceptionCaught(ex);
    }

    @Test
    void destroyComponent_withoutStart_doesNotThrow() throws Exception {
        NettyHttpServer server = new NettyHttpServer(mock(WebContext.class));
        assertDoesNotThrow(server::destroyComponent);
    }

    /** SslExceptionHandler 实例单例可复用（@Sharable） */
    @Test
    void sslExceptionHandler_singleton() {
        assertSame(NettyHttpServer.SslExceptionHandler.INSTANCE,
                NettyHttpServer.SslExceptionHandler.INSTANCE);
    }
}