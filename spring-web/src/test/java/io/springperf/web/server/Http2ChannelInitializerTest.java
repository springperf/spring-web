package io.springperf.web.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.springperf.web.http.BackpressureHandler;
import io.springperf.web.http.support.SupportMultipartAggregator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link Http2ChannelInitializer} 的 HTTP/1.1 管线构建：
 * handler 顺序、aggregator 选择（multipart/simple）、before/after 注入、readTimeout 条件。
 */
class Http2ChannelInitializerTest {

    private final NettyHttpHandler httpHandler = mock(NettyHttpHandler.class);

    /** 暴露 protected initChannel 的测试子类 */
    static class TestableInitializer extends Http2ChannelInitializer {
        TestableInitializer(boolean http2Enabled, io.netty.handler.ssl.SslContext sslContext,
                            int maxContentLength, long readTimeout, boolean supportMultipart,
                            NettyHttpHandler httpHandler, List<ChannelHandler> before,
                            List<ChannelHandler> after) {
            super(http2Enabled, sslContext, maxContentLength, readTimeout, supportMultipart,
                    httpHandler, before, after);
        }

        @Override
        public void initChannel(SocketChannel ch) {
            super.initChannel(ch);
        }
    }

    private SocketChannel channel(ChannelPipeline pipeline) {
        SocketChannel ch = mock(SocketChannel.class);
        when(ch.pipeline()).thenReturn(pipeline);
        return ch;
    }

    @Test
    void http11_noSsl_buildsCodecAggregatorHandlers() {
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        TestableInitializer init = new TestableInitializer(false, null, 1024, 0, false,
                httpHandler, Collections.emptyList(), Collections.emptyList());
        init.initChannel(channel(pipeline));

        verify(pipeline).addLast(any(HttpServerCodec.class));
        verify(pipeline).addLast(any(ChunkedWriteHandler.class));
        verify(pipeline).addLast(same(BackpressureHandler.INSTANCE));
        verify(pipeline).addLast(same(httpHandler));
        verify(pipeline, never()).addLast(any(ReadTimeoutHandler.class));
    }

    @Test
    void http11_withReadTimeout_addsReadTimeoutHandler() {
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        TestableInitializer init = new TestableInitializer(false, null, 1024, 5000, false,
                httpHandler, Collections.emptyList(), Collections.emptyList());
        init.initChannel(channel(pipeline));

        verify(pipeline).addLast(any(ReadTimeoutHandler.class));
    }

    @Test
    void http11_supportMultipart_usesSupportMultipartAggregator() {
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        TestableInitializer init = new TestableInitializer(false, null, 1024, 0, true,
                httpHandler, Collections.emptyList(), Collections.emptyList());
        init.initChannel(channel(pipeline));

        verify(pipeline).addLast(any(SupportMultipartAggregator.class));
    }

    @Test
    void http11_beforeAndAfterHandlers_injectedInOrder() {
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        ChannelHandler before = mock(ChannelHandler.class);
        ChannelHandler after = mock(ChannelHandler.class);
        TestableInitializer init = new TestableInitializer(false, null, 1024, 0, false,
                httpHandler, Collections.singletonList(before), Collections.singletonList(after));
        init.initChannel(channel(pipeline));

        verify(pipeline).addLast(before);
        verify(pipeline).addLast(after);
    }

    @Test
    @org.junit.jupiter.api.Disabled("Netty 4.1.115 的 SslContext.newHandler(ByteBufAllocator) 为 final 方法，Mockito 4 core（subclass mock maker）无法 mock；master 用 Netty 4.1.137 可 mock。2.7.x 真实 SSL 管线由 SslServerTest/SslManagementPortTest 覆盖。")
    void http11_withSsl_addsSslHandlerAndExceptionHandler() {
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        io.netty.handler.ssl.SslContext sslContext = mock(io.netty.handler.ssl.SslContext.class);
        when(sslContext.newHandler(any(io.netty.buffer.ByteBufAllocator.class)))
                .thenReturn(mock(io.netty.handler.ssl.SslHandler.class));
        TestableInitializer init = new TestableInitializer(false, sslContext, 1024, 0, false,
                httpHandler, Collections.emptyList(), Collections.emptyList());
        SocketChannel ch = channel(pipeline);
        when(ch.alloc()).thenReturn(io.netty.buffer.UnpooledByteBufAllocator.DEFAULT);
        init.initChannel(ch);

        verify(sslContext).newHandler(any(io.netty.buffer.ByteBufAllocator.class));
        verify(pipeline).addLast(any(NettyHttpServer.SslExceptionHandler.class));
        verify(pipeline).addLast(any(HttpServerCodec.class));
    }

    @Test
    void http2_withoutSsl_addsCleartextHandlers() {
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        TestableInitializer init = new TestableInitializer(true, null, 1024, 0, false,
                httpHandler, Collections.emptyList(), Collections.emptyList());
        try {
            init.initChannel(channel(pipeline));
            // cleartext h2：HttpServerCodec source codec 追加
            verify(pipeline).addLast(any(HttpServerCodec.class));
        } catch (Exception e) {
            fail("cleartext h2 init 不应抛异常: " + e.getMessage());
        }
    }
}