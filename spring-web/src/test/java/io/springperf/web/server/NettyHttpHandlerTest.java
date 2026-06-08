package io.springperf.web.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.SimpleChannelInboundHandler;
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
    void isSimpleChannelInboundHandler() {
        WebContext webContext = mock(WebContext.class);
        HttpHandler handler = mock(HttpHandler.class);
        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "", handler);

        assertTrue(nettyHandler instanceof SimpleChannelInboundHandler);
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
    void acceptsFullHttpRequestType() throws Exception {
        WebContext webContext = mock(WebContext.class);
        HttpHandler handler = mock(HttpHandler.class);
        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "", handler);

        assertTrue(nettyHandler.acceptInboundMessage(mock(FullHttpRequest.class)));
    }

    @Test
    void doesNotAcceptNonHttpMessages() throws Exception {
        WebContext webContext = mock(WebContext.class);
        HttpHandler handler = mock(HttpHandler.class);
        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "", handler);

        assertFalse(nettyHandler.acceptInboundMessage("not a http request"));
    }
}