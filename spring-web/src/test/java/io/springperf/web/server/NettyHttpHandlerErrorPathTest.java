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
    void setUp() throws Exception {
        handler = mock(HttpHandler.class);
        lenient().when(webContext.getProps()).thenReturn(appProperties);
        // 固定合法的内存上限值：mock 默认返回 0 会永久污染 NettyServerHttpRequest 静态缓存
        lenient().when(appProperties.getInt(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE))
                .thenReturn(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE_DEFAULT);
        // 每个用例前复位静态缓存，避免用例间互相污染（如构造失败用例的 stub）
        resetLargeBodyLimitCache();
    }

    private void resetLargeBodyLimitCache() throws Exception {
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

    @Test
    void requestConstructionFailure_releasesRetainedBuf() throws Exception {
        // 构造 NettyServerHttpRequest 阶段抛异常：msg.retain() 的引用必须被释放（refCnt 归零），
        // 否则 ByteBuf 泄漏。先复位静态缓存，使 getProps().getInt() 真正被调用并抛异常。
        resetLargeBodyLimitCache();
        when(appProperties.getInt(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE))
                .thenThrow(new RuntimeException("boom during request construction"));

        NettyHttpHandler nettyHandler = new NettyHttpHandler(webContext, "", handler);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(nettyHandler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        nettyHandler.channelRead(channel.pipeline().firstContext(), request);

        // 请求未委托给 handler
        verify(handler, never()).httpHandle(any(), any());
        // retain 的引用已被释放，无泄漏
        assertEquals(0, request.refCnt(), "构造失败路径必须释放 retain 的 ByteBuf");

        channel.finishAndReleaseAll();
    }
}
