package io.springperf.web.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

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

        // 非 HttpObject 消息应透传，不抛异常：直接由 EmbeddedChannel 末端的 handler 静默消化
        channel.pipeline().fireChannelRead("not a http request");
    }
}