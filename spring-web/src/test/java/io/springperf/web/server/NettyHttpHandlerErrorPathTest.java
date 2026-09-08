package io.springperf.web.server;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.NettyServerHttpRequest;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NettyHttpHandlerErrorPathTest {

    @Mock
    WebContext webContext;

    @Mock
    ApplicationProperties appProperties;

    HttpHandler handler;

    @BeforeEach
    void setUp() {
        handler = mock(HttpHandler.class);
        lenient().when(webContext.getProps()).thenReturn(appProperties);
        // 固定合法的内存上限值：mock 默认返回 0 会永久污染 NettyServerHttpRequest 静态缓存
        lenient().when(appProperties.getInt(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE))
                .thenReturn(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE_DEFAULT);
    }

    @AfterEach
    void resetStaticCache() throws Exception {
        Field field = NettyServerHttpRequest.class.getDeclaredField("cachedLargeBodyLimit");
        field.setAccessible(true);
        field.setInt(null, -1);
    }

    @Test
    void contextPathMismatch_sendsNotFound() throws Exception {
        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "/app", handler);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(nettyHandler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/other");
        nettyHandler.channelRead(channel.pipeline().firstContext(), request);

        verify(handler, never()).httpHandle(any(), any());

        HttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(404, response.status().code());
    }

    @Test
    void normalRequest_callsHandler() throws Exception {
        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "", handler);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(nettyHandler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        nettyHandler.channelRead(channel.pipeline().firstContext(), request);

        verify(handler).httpHandle(any(WebServerHttpRequest.class), any(WebServerHttpResponse.class));
    }

    @Test
    void contextPathEquals_root() throws Exception {
        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "/app", handler);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(nettyHandler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/app");
        nettyHandler.channelRead(channel.pipeline().firstContext(), request);

        verify(handler).httpHandle(any(WebServerHttpRequest.class), any(WebServerHttpResponse.class));
    }

    @Test
    void contextPathStripped() throws Exception {
        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "/app", handler);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(nettyHandler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/app/hello");
        nettyHandler.channelRead(channel.pipeline().firstContext(), request);

        verify(handler).httpHandle(any(WebServerHttpRequest.class), any(WebServerHttpResponse.class));
    }

    @Test
    void handlerException_sendsInternalServerError() throws Exception {
        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "", handler);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(nettyHandler);

        doThrow(new RuntimeException("handler error")).when(handler)
                .httpHandle(any(WebServerHttpRequest.class), any(WebServerHttpResponse.class));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        nettyHandler.channelRead(channel.pipeline().firstContext(), request);

        verify(handler).httpHandle(any(), any());

        HttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(500, response.status().code());
    }
}
