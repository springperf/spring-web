package io.springperf.web.server;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.springperf.web.http.BackpressureHandler;
import io.springperf.web.http.support.SupportMultipartAggregator;

/**
 * 验证 {@link Http2ChannelInitializer} 的 HTTP/1.1 管线构建： handler 顺序、aggregator 选择（multipart/simple）、before/after
 * 注入、readTimeout 条件。
 */
class Http2ChannelInitializerTest {

    private final NettyHttpHandler httpHandler = mock(NettyHttpHandler.class);

    /** 暴露 protected initChannel 的测试子类 */
    static class TestableInitializer extends Http2ChannelInitializer {
        TestableInitializer(boolean http2Enabled, io.netty.handler.ssl.SslContext sslContext, int maxContentLength,
                long readTimeout, boolean supportMultipart, NettyHttpHandler httpHandler, List<ChannelHandler> before,
                List<ChannelHandler> after) {
            super(http2Enabled, sslContext, maxContentLength, readTimeout, supportMultipart, httpHandler, before,
                    after);
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
        TestableInitializer init = new TestableInitializer(false, null, 1024, 0, false, httpHandler,
                Collections.emptyList(), Collections.emptyList());
        init.initChannel(channel(pipeline));

        verify(pipeline).addLast(any(HttpServerCodec.class));
        verify(pipeline).addLast(any(ChunkedWriteHandler.class));
        verify(pipeline).addLast(same(BackpressureHandler.INSTANCE));
        verify(pipeline).addLast(same(httpHandler));
        verify(pipeline, never()).addLast(any(ReadIdleTimeoutHandler.class));
    }

    @Test
    void http11_withReadTimeout_addsReadIdleTimeoutHandler() {
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        TestableInitializer init = new TestableInitializer(false, null, 1024, 5000, false, httpHandler,
                Collections.emptyList(), Collections.emptyList());
        init.initChannel(channel(pipeline));

        verify(pipeline).addLast(any(ReadIdleTimeoutHandler.class));
    }

    /**
     * 默认值（{@code server.http.read-timeout} 未配置 ⇒ {@code HTTP_READ_TIMEOUT_DEFAULT}）下**不装**读空闲 handler：默认不施加连接级隐式限制。
     * 若哪天默认值被改回正数，本用例会红——这正是它存在的意义。
     */
    @Test
    void http11_defaultReadTimeout_addsNoReadIdleTimeoutHandler() {
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        TestableInitializer init = new TestableInitializer(false, null, 1024,
                io.springperf.web.context.PropertiesConstant.HTTP_READ_TIMEOUT_DEFAULT, false, httpHandler,
                Collections.emptyList(), Collections.emptyList());
        init.initChannel(channel(pipeline));

        verify(pipeline, never()).addLast(any(ReadIdleTimeoutHandler.class));
    }

    @Test
    void http11_supportMultipart_usesSupportMultipartAggregator() {
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        TestableInitializer init = new TestableInitializer(false, null, 1024, 0, true, httpHandler,
                Collections.emptyList(), Collections.emptyList());
        init.initChannel(channel(pipeline));

        verify(pipeline).addLast(any(SupportMultipartAggregator.class));
    }

    @Test
    void http11_beforeAndAfterHandlers_injectedInOrder() {
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        ChannelHandler before = mock(ChannelHandler.class);
        ChannelHandler after = mock(ChannelHandler.class);
        TestableInitializer init = new TestableInitializer(false, null, 1024, 0, false, httpHandler,
                Collections.singletonList(before), Collections.singletonList(after));
        init.initChannel(channel(pipeline));

        verify(pipeline).addLast(before);
        verify(pipeline).addLast(after);
    }

    @Test
    void http11_withSsl_addsSslHandlerAndExceptionHandler() {
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        io.netty.handler.ssl.SslContext sslContext = mock(io.netty.handler.ssl.SslContext.class);
        when(sslContext.newHandler(any(io.netty.buffer.ByteBufAllocator.class)))
                .thenReturn(mock(io.netty.handler.ssl.SslHandler.class));
        TestableInitializer init = new TestableInitializer(false, sslContext, 1024, 0, false, httpHandler,
                Collections.emptyList(), Collections.emptyList());
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
        TestableInitializer init = new TestableInitializer(true, null, 1024, 0, false, httpHandler,
                Collections.emptyList(), Collections.emptyList());
        try {
            init.initChannel(channel(pipeline));
            // cleartext h2：HttpServerCodec source codec 追加
            verify(pipeline).addLast(any(HttpServerCodec.class));
        } catch (Exception e) {
            fail("cleartext h2 init 不应抛异常: " + e.getMessage());
        }
    }

    // ==================== 管线顺序：keep-alive 必须在 httpHandler 之前 ====================

    /** 暴露带 KeepAliveConfig 的完整构造。 */
    static class TestableKeepAliveInitializer extends Http2ChannelInitializer {
        TestableKeepAliveInitializer(boolean http2Enabled, int maxContentLength, long readTimeout,
                boolean supportMultipart, NettyHttpHandler httpHandler, KeepAliveConfig keepAliveConfig) {
            super(http2Enabled, null, maxContentLength, readTimeout, supportMultipart, httpHandler,
                    Collections.emptyList(), Collections.emptyList(), 4096, 8192, 8192, -1, 8192,
                    CompressionConfig.DISABLED, keepAliveConfig);
        }

        @Override
        public void initChannel(SocketChannel ch) {
            super.initChannel(ch);
        }
    }

    @Test
    void http11_pipelineOrder_keepAliveHandlerBeforeHttpHandler() {
        // NettyHttpHandler 是入站终端且用自身 ctx 写响应：KeepAliveHandler 若排在其后
        // 将收不到任何事件（曾因此回归）。用真实 pipeline 锁死顺序。
        io.netty.channel.embedded.EmbeddedChannel embedded = new io.netty.channel.embedded.EmbeddedChannel();
        TestableKeepAliveInitializer init = new TestableKeepAliveInitializer(false, 1024, 0, false, httpHandler,
                new KeepAliveConfig(1000L, 5));
        init.initChannel(channel(embedded.pipeline()));

        ChannelPipeline pipeline = embedded.pipeline();
        int keepAliveIdx = -1;
        int httpHandlerIdx = -1;
        for (int i = 0; i < pipeline.names().size(); i++) {
            String name = pipeline.names().get(i);
            if (pipeline.get(name) instanceof KeepAliveHandler) {
                keepAliveIdx = i;
            }
            if (pipeline.get(name) == httpHandler) {
                httpHandlerIdx = i;
            }
        }
        assertTrue(keepAliveIdx >= 0, "keep-alive handler 应注入管线");
        assertTrue(httpHandlerIdx >= 0);
        assertTrue(keepAliveIdx < httpHandlerIdx, "KeepAliveHandler 必须位于 NettyHttpHandler 之前，实际 keep-alive="
                + keepAliveIdx + " httpHandler=" + httpHandlerIdx + " names=" + pipeline.names());
    }
}
