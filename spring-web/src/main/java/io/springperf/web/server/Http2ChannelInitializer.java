package io.springperf.web.server;

import static io.springperf.web.context.PropertiesConstant.HTTP_MULTIPART_MAX_PART_HEADER_SIZE_DEFAULT;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.springperf.web.http.BackpressureHandler;
import io.springperf.web.http.support.SupportMultipartAggregator;

public class Http2ChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final boolean http2Enabled;
    private final SslContext sslContext;
    private final int maxContentLength;
    private final long readTimeout;
    private final boolean supportMultipart;
    private final NettyHttpHandler httpHandler;
    private final List<ChannelHandler> beforeAggregatorHandlers;
    private final List<ChannelHandler> afterAggregatorHandlers;
    private final int maxInitialLineLength;
    private final int maxHeaderSize;
    private final int maxChunkSize;
    private final int maxPartCount;
    private final int maxPartHeaderSize;
    private final CompressionConfig compressionConfig;
    private final KeepAliveConfig keepAliveConfig;
    /** multipart 上传配置（spring.servlet.multipart.*）：默认不限制，由 server 按配置注入。 */
    private MultipartConfig multipartConfig = MultipartConfig.DEFAULT;

    public Http2ChannelInitializer(boolean http2Enabled, SslContext sslContext, int maxContentLength, long readTimeout,
            boolean supportMultipart, NettyHttpHandler httpHandler) {
        this(http2Enabled, sslContext, maxContentLength, readTimeout, supportMultipart, httpHandler,
                Collections.emptyList(), Collections.emptyList());
    }

    public Http2ChannelInitializer(boolean http2Enabled, SslContext sslContext, int maxContentLength, long readTimeout,
            boolean supportMultipart, NettyHttpHandler httpHandler, List<ChannelHandler> beforeAggregatorHandlers) {
        this(http2Enabled, sslContext, maxContentLength, readTimeout, supportMultipart, httpHandler,
                beforeAggregatorHandlers, Collections.emptyList());
    }

    public Http2ChannelInitializer(boolean http2Enabled, SslContext sslContext, int maxContentLength, long readTimeout,
            boolean supportMultipart, NettyHttpHandler httpHandler, List<ChannelHandler> beforeAggregatorHandlers,
            List<ChannelHandler> afterAggregatorHandlers) {
        this(http2Enabled, sslContext, maxContentLength, readTimeout, supportMultipart, httpHandler,
                beforeAggregatorHandlers, afterAggregatorHandlers, 4096, 8192, 8192, -1,
                HTTP_MULTIPART_MAX_PART_HEADER_SIZE_DEFAULT, CompressionConfig.DISABLED, KeepAliveConfig.DISABLED);
    }

    public Http2ChannelInitializer(boolean http2Enabled, SslContext sslContext, int maxContentLength, long readTimeout,
            boolean supportMultipart, NettyHttpHandler httpHandler, List<ChannelHandler> beforeAggregatorHandlers,
            List<ChannelHandler> afterAggregatorHandlers, int maxInitialLineLength, int maxHeaderSize, int maxChunkSize,
            int maxPartCount, int maxPartHeaderSize, CompressionConfig compressionConfig,
            KeepAliveConfig keepAliveConfig) {
        this.http2Enabled = http2Enabled;
        this.sslContext = sslContext;
        this.maxContentLength = maxContentLength;
        this.readTimeout = readTimeout;
        this.supportMultipart = supportMultipart;
        this.httpHandler = httpHandler;
        this.beforeAggregatorHandlers = beforeAggregatorHandlers != null ? beforeAggregatorHandlers
                : Collections.emptyList();
        this.afterAggregatorHandlers = afterAggregatorHandlers != null ? afterAggregatorHandlers
                : Collections.emptyList();
        this.maxInitialLineLength = maxInitialLineLength;
        this.maxHeaderSize = maxHeaderSize;
        this.maxChunkSize = maxChunkSize;
        this.maxPartCount = maxPartCount;
        this.maxPartHeaderSize = maxPartHeaderSize;
        this.compressionConfig = compressionConfig;
        this.keepAliveConfig = keepAliveConfig;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();

        if (sslContext != null) {
            p.addLast(sslContext.newHandler(ch.alloc()));
            p.addLast(NettyHttpServer.SslExceptionHandler.INSTANCE);
            if (http2Enabled) {
                p.addLast(new Http2OrHttp1Handler(maxContentLength, readTimeout, supportMultipart, httpHandler,
                        beforeAggregatorHandlers, afterAggregatorHandlers, maxInitialLineLength, maxHeaderSize,
                        maxChunkSize, maxPartCount, maxPartHeaderSize, multipartConfig, compressionConfig,
                        keepAliveConfig));
            } else {
                addHttp11Handlers(p);
            }
        } else if (http2Enabled) {
            addCleartextHttp2Handlers(p);
        } else {
            addHttp11Handlers(p);
        }
    }

    private void addHttp11Handlers(ChannelPipeline p) {
        p.addLast(new HttpServerCodec(maxInitialLineLength, maxHeaderSize, maxChunkSize));
        addCompressor(p, compressionConfig);
        if (readTimeout > 0) {
            p.addLast(new ReadIdleTimeoutHandler(readTimeout));
        }
        p.addLast(new ChunkedWriteHandler());
        for (ChannelHandler h : beforeAggregatorHandlers) {
            p.addLast(h);
        }
        addAggregator(p);
        for (ChannelHandler h : afterAggregatorHandlers) {
            p.addLast(h);
        }
        p.addLast(BackpressureHandler.INSTANCE);
        // keep-alive 调优 handler 必须位于 httpHandler 之前：入站先计数/取消空闲计时再转发
        // （httpHandler 是入站终端），出站响应经其注入 Connection: close 并调度空闲超时。
        addKeepAlive(p, keepAliveConfig);
        p.addLast(httpHandler);
    }

    private static final byte[] H2_PREFACE_BYTES = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    private void addCleartextHttp2Handlers(ChannelPipeline p) {
        // h2c prior knowledge: use a preface detector that switches to HTTP/2
        // when it detects the "PRI * HTTP/2.0" preface, or falls through to HTTP/1.1.
        HttpServerCodec sourceCodec = new HttpServerCodec(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        Http2FrameCodec frameCodec = Http2FrameCodecBuilder.forServer().build();
        Http2MultiplexHandler multiplexHandler = new Http2MultiplexHandler(
                new Http2ChildChannelInitializer(maxContentLength, supportMultipart, httpHandler, maxPartCount,
                        maxPartHeaderSize, multipartConfig, compressionConfig, keepAliveConfig));

        p.addLast(new ChannelInboundHandlerAdapter() {
            private ByteBuf accumulator;

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (!(msg instanceof ByteBuf)) {
                    ctx.fireChannelRead(msg);
                    return;
                }
                ByteBuf buf = (ByteBuf) msg;

                // Common case: entire preface fits in one chunk
                if (buf.readableBytes() >= H2_PREFACE_BYTES.length) {
                    ChannelPipeline pipe = ctx.pipeline();
                    if (startsWithPreface(buf)) {
                        // h2c detected: add h2 codecs before this handler, fire from head
                        pipe.addBefore(pipe.context(this).name(), "h2-frame-codec", frameCodec);
                        pipe.addAfter("h2-frame-codec", "h2-multiplex", multiplexHandler);
                        pipe.remove(this);
                        pipe.fireChannelRead(msg);
                    } else {
                        pipe.remove(this);
                        pipe.fireChannelRead(msg);
                    }
                    return;
                }

                // Fragmented: buffer bytes until we have 24+
                if (accumulator == null) {
                    accumulator = ctx.alloc().buffer(H2_PREFACE_BYTES.length);
                }
                try {
                    accumulator.writeBytes(buf);
                } finally {
                    // 内容已拷贝进 accumulator，buf 不再被传递（整包路径 fireChannelRead(msg) 转移所有权，
                    // 分片路径仅拷贝），必须立即释放引用，否则每个 fragment 泄漏一个 ByteBuf。
                    buf.release();
                }

                if (accumulator.readableBytes() < H2_PREFACE_BYTES.length) {
                    return; // wait for more fragments
                }

                // We have enough — decide
                ChannelPipeline pipe = ctx.pipeline();
                accumulator.retain(); // retain before remove(this) frees it
                if (startsWithPreface(accumulator)) {
                    pipe.addBefore(pipe.context(this).name(), "h2-frame-codec", frameCodec);
                    pipe.addAfter("h2-frame-codec", "h2-multiplex", multiplexHandler);
                    pipe.remove(this);
                    pipe.fireChannelRead(accumulator);
                } else {
                    pipe.remove(this);
                    pipe.fireChannelRead(accumulator);
                }
            }

            @Override
            public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
                if (accumulator != null) {
                    accumulator.release();
                    accumulator = null;
                }
                super.handlerRemoved(ctx);
            }

            private boolean startsWithPreface(ByteBuf buf) {
                if (buf.readableBytes() < H2_PREFACE_BYTES.length) {
                    return false;
                }
                for (int i = 0; i < H2_PREFACE_BYTES.length; i++) {
                    if (buf.getByte(i) != H2_PREFACE_BYTES[i]) {
                        return false;
                    }
                }
                return true;
            }
        });
        // HTTP/1.1 fallback pipeline
        p.addLast(sourceCodec);
        addCompressor(p, compressionConfig);
        if (readTimeout > 0) {
            p.addLast(new ReadIdleTimeoutHandler(readTimeout));
        }
        p.addLast(new ChunkedWriteHandler());
        for (ChannelHandler h : beforeAggregatorHandlers) {
            p.addLast(h);
        }
        addAggregator(p);
        for (ChannelHandler h : afterAggregatorHandlers) {
            p.addLast(h);
        }
        p.addLast(BackpressureHandler.INSTANCE);
        // keep-alive 调优 handler 必须位于 httpHandler 之前：入站先计数/取消空闲计时再转发
        // （httpHandler 是入站终端），出站响应经其注入 Connection: close 并调度空闲超时。
        addKeepAlive(p, keepAliveConfig);
        p.addLast(httpHandler);
    }

    /** 注入 multipart 上传配置（spring.servlet.multipart.*）；须在 initChannel 前调用。 */
    public Http2ChannelInitializer multipartConfig(MultipartConfig multipartConfig) {
        if (multipartConfig != null) {
            this.multipartConfig = multipartConfig;
        }
        return this;
    }

    private void addAggregator(ChannelPipeline p) {
        if (supportMultipart) {
            p.addLast(new SupportMultipartAggregator(maxContentLength, maxPartCount, maxPartHeaderSize,
                    multipartConfig.getMaxFileSize(), multipartConfig.getFileSizeThreshold(),
                    multipartConfig.getLocation()));
        } else {
            p.addLast(new HttpObjectAggregator(maxContentLength));
        }
    }

    /**
     * 按配置在 codec 之后注入响应 gzip 压缩器（压缩器同时是入站/出站 handler：入站从请求头取 Accept-Encoding，出站压缩响应体）。关闭时（{@code cfg} 为
     * {@code DISABLED}）不注入，零开销。
     */
    private static void addCompressor(ChannelPipeline p, CompressionConfig cfg) {
        if (cfg != null && cfg.isEnabled()) {
            p.addLast(new SupportHttpContentCompressor(cfg));
        }
    }

    /**
     * 按配置在 {@code NettyHttpHandler} 之后注入 keep-alive 调优 handler： 单连接请求计数达到上限后关闭、连接空闲超时后关闭。关闭时（{@code cfg} 为
     * {@code DISABLED}）不注入。
     */
    private static void addKeepAlive(ChannelPipeline p, KeepAliveConfig cfg) {
        if (cfg != null && cfg.isEnabled()) {
            p.addLast(new KeepAliveHandler(cfg.getTimeoutMillis(), cfg.getMaxRequests()));
        }
    }

    /**
     * HTTP/2 child channel initializer. Each HTTP/2 stream gets its own child channel with pipeline:
     * Http2StreamFrameToHttpObjectCodec -> ChunkedWriteHandler -> SupportMultipartAggregator(or HttpObjectAggregator)
     * -> BackpressureHandler -> NettyHttpHandler
     */
    private static class Http2ChildChannelInitializer extends ChannelInitializer<Channel> {
        private final int maxContentLength;
        private final boolean supportMultipart;
        private final NettyHttpHandler httpHandler;
        private final int maxPartCount;
        private final int maxPartHeaderSize;
        private final MultipartConfig multipartConfig;
        private final CompressionConfig compressionConfig;
        private final KeepAliveConfig keepAliveConfig;

        Http2ChildChannelInitializer(int maxContentLength, boolean supportMultipart, NettyHttpHandler httpHandler,
                int maxPartCount, int maxPartHeaderSize, MultipartConfig multipartConfig,
                CompressionConfig compressionConfig, KeepAliveConfig keepAliveConfig) {
            this.maxContentLength = maxContentLength;
            this.supportMultipart = supportMultipart;
            this.httpHandler = httpHandler;
            this.maxPartCount = maxPartCount;
            this.maxPartHeaderSize = maxPartHeaderSize;
            this.multipartConfig = multipartConfig;
            this.compressionConfig = compressionConfig;
            this.keepAliveConfig = keepAliveConfig;
        }

        @Override
        protected void initChannel(Channel ch) {
            ChannelPipeline p = ch.pipeline();
            // true = server side: incoming headers -> HttpRequest, outgoing HttpResponse -> headers
            p.addLast(new Http2StreamFrameToHttpObjectCodec(true));
            addCompressor(p, compressionConfig);
            p.addLast(new ChunkedWriteHandler());
            if (supportMultipart) {
                p.addLast(new SupportMultipartAggregator(maxContentLength, maxPartCount, maxPartHeaderSize,
                        multipartConfig.getMaxFileSize(), multipartConfig.getFileSizeThreshold(),
                        multipartConfig.getLocation()));
            } else {
                p.addLast(new HttpObjectAggregator(maxContentLength));
            }
            p.addLast(BackpressureHandler.INSTANCE);
            // 同上：keep-alive handler 需在 httpHandler 之前（见 initChannel 注释）
            addKeepAlive(p, keepAliveConfig);
            p.addLast(httpHandler);
        }
    }

    /**
     * ALPN protocol negotiation handler. After TLS handshake, selects h2 or h1.1 pipeline based on ALPN result.
     */
    private static class Http2OrHttp1Handler extends ApplicationProtocolNegotiationHandler {

        private final int maxContentLength;
        private final long readTimeout;
        private final boolean supportMultipart;
        private final NettyHttpHandler httpHandler;
        private final List<ChannelHandler> beforeAggregatorHandlers;
        private final List<ChannelHandler> afterAggregatorHandlers;
        private final int maxInitialLineLength;
        private final int maxHeaderSize;
        private final int maxChunkSize;
        private final int maxPartCount;
        private final int maxPartHeaderSize;
        private final MultipartConfig multipartConfig;
        private final CompressionConfig compressionConfig;
        private final KeepAliveConfig keepAliveConfig;

        Http2OrHttp1Handler(int maxContentLength, long readTimeout, boolean supportMultipart,
                NettyHttpHandler httpHandler, List<ChannelHandler> beforeAggregatorHandlers,
                List<ChannelHandler> afterAggregatorHandlers, int maxInitialLineLength, int maxHeaderSize,
                int maxChunkSize, int maxPartCount, int maxPartHeaderSize, MultipartConfig multipartConfig,
                CompressionConfig compressionConfig, KeepAliveConfig keepAliveConfig) {
            super(ApplicationProtocolNames.HTTP_1_1);
            this.maxContentLength = maxContentLength;
            this.readTimeout = readTimeout;
            this.supportMultipart = supportMultipart;
            this.httpHandler = httpHandler;
            this.beforeAggregatorHandlers = beforeAggregatorHandlers != null ? beforeAggregatorHandlers
                    : Collections.emptyList();
            this.afterAggregatorHandlers = afterAggregatorHandlers != null ? afterAggregatorHandlers
                    : Collections.emptyList();
            this.maxInitialLineLength = maxInitialLineLength;
            this.maxHeaderSize = maxHeaderSize;
            this.maxChunkSize = maxChunkSize;
            this.maxPartCount = maxPartCount;
            this.maxPartHeaderSize = maxPartHeaderSize;
            this.multipartConfig = multipartConfig;
            this.compressionConfig = compressionConfig;
            this.keepAliveConfig = keepAliveConfig;
        }

        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
            ChannelPipeline p = ctx.pipeline();
            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                // h2: remove SslExceptionHandler (h1.1 specific), add h2 pipeline
                p.remove(NettyHttpServer.SslExceptionHandler.class);
                p.addLast(Http2FrameCodecBuilder.forServer().build());
                p.addLast(new Http2MultiplexHandler(
                        new Http2ChildChannelInitializer(maxContentLength, supportMultipart, httpHandler, maxPartCount,
                                maxPartHeaderSize, multipartConfig, compressionConfig, keepAliveConfig)));
            } else {
                // http/1.1: add standard h1.1 pipeline
                p.addLast(new HttpServerCodec(maxInitialLineLength, maxHeaderSize, maxChunkSize));
                addCompressor(p, compressionConfig);
                if (readTimeout > 0) {
                    p.addLast(new ReadIdleTimeoutHandler(readTimeout));
                }
                p.addLast(new ChunkedWriteHandler());
                for (ChannelHandler h : beforeAggregatorHandlers) {
                    p.addLast(h);
                }
                if (supportMultipart) {
                    p.addLast(new SupportMultipartAggregator(maxContentLength, maxPartCount, maxPartHeaderSize,
                            multipartConfig.getMaxFileSize(), multipartConfig.getFileSizeThreshold(),
                            multipartConfig.getLocation()));
                } else {
                    p.addLast(new HttpObjectAggregator(maxContentLength));
                }
                for (ChannelHandler h : afterAggregatorHandlers) {
                    p.addLast(h);
                }
                p.addLast(BackpressureHandler.INSTANCE);
                // 同上：keep-alive handler 需在 httpHandler 之前（见 initChannel 注释）
                addKeepAlive(p, keepAliveConfig);
                p.addLast(httpHandler);
            }
        }
    }
}
