package io.springperf.web.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NettyHttpHandlerTest {

    @Test
    void constructor_acceptsWebContextContextPathAndHandler() {
        WebContext webContext = mock(WebContext.class);
        HttpHandler handler = mock(HttpHandler.class);

        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "/app", handler);

        assertNotNull(nettyHandler);
    }

    @Test
    void constructor_acceptsEmptyContextPath() {
        WebContext webContext = mock(WebContext.class);
        HttpHandler handler = mock(HttpHandler.class);

        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "", handler);

        assertNotNull(nettyHandler);
    }

    @Test
    void isChannelInboundHandlerAdapter() {
        WebContext webContext = mock(WebContext.class);
        HttpHandler handler = mock(HttpHandler.class);
        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "", handler);

        assertTrue(nettyHandler instanceof ChannelInboundHandlerAdapter);
    }

    @Test
    void isSharable() {
        WebContext webContext = mock(WebContext.class);
        HttpHandler handler = mock(HttpHandler.class);
        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "", handler);

        ChannelHandler.Sharable sharable = nettyHandler.getClass().getAnnotation(ChannelHandler.Sharable.class);
        assertNotNull(sharable);
    }

    @Test
    void forwardsNonHttpMessage() throws Exception {
        WebContext webContext = mock(WebContext.class);
        HttpHandler handler = mock(HttpHandler.class);
        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "", handler);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(nettyHandler);
        // 尾部 handler 捕获透传消息，验证非 HttpObject 消息不被 HttpHandler 处理而是原样下发
        final java.util.List<Object> received = new java.util.ArrayList<>();
        channel.pipeline().addLast(new io.netty.channel.ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                received.add(msg);
            }
        });

        channel.pipeline().fireChannelRead("not a http request");

        assertEquals(java.util.Collections.singletonList("not a http request"), received,
                "非 HttpObject 消息应透传到链尾，不被吞掉");
        verify(handler, never()).httpHandle(any(), any());
    }
}