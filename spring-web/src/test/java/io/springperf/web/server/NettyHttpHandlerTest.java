package io.springperf.web.server;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
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
}