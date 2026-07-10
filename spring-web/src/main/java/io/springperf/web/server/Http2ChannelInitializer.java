package io.springperf.web.server;

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
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.springperf.web.http.BackpressureHandler;
import io.springperf.web.http.support.SupportMultipartAggregator;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    public Http2ChannelInitializer(boolean http2Enabled, SslContext sslContext,
                                    int maxContentLength, long readTimeout,
                                    boolean supportMultipart,
                                    NettyHttpHandler httpHandler) {
        this(http2Enabled, sslContext, maxContentLength, readTimeout, supportMultipart, httpHandler,
                Collections.emptyList(), Collections.emptyList());
    }

    public Http2ChannelInitializer(boolean http2Enabled, SslContext sslContext,
                                    int maxContentLength, long readTimeout,
                                    boolean supportMultipart,
                                    NettyHttpHandler httpHandler,
                                    List<ChannelHandler> beforeAggregatorHandlers) {
        this(http2Enabled, sslContext, maxContentLength, readTimeout, supportMultipart, httpHandler,
                beforeAggregatorHandlers, Collections.emptyList());
    }

    public Http2ChannelInitializer(boolean http2Enabled, SslContext sslContext,
                                    int maxContentLength, long readTimeout,
                                    boolean supportMultipart,
                                    NettyHttpHandler httpHandler,
                                    List<ChannelHandler> beforeAggregatorHandlers,
                                    List<ChannelHandler> afterAggregatorHandlers) {
        this(http2Enabled, sslContext, maxContentLength, readTimeout, supportMultipart, httpHandler,
                beforeAggregatorHandlers, afterAggregatorHandlers,
                4096, 8192, 8192);
    }

    public Http2ChannelInitializer(boolean http2Enabled, SslContext sslContext,
                                    int maxContentLength, long readTimeout,
                                    boolean supportMultipart,
                                    NettyHttpHandler httpHandler,
                                    List<ChannelHandler> beforeAggregatorHandlers,
                                    List<ChannelHandler> afterAggregatorHandlers,
                                    int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
        this.http2Enabled = http2Enabled;
        this.sslContext = sslContext;
        this.maxContentLength = maxContentLength;
        this.readTimeout = readTimeout;
        this.supportMultipart = supportMultipart;
        this.httpHandler = httpHandler;
        this.beforeAggregatorHandlers = beforeAggregatorHandlers != null
                ? beforeAggregatorHandlers : Collections.emptyList();
        this.afterAggregatorHandlers = afterAggregatorHandlers != null
                ? afterAggregatorHandlers : Collections.emptyList();
        this.maxInitialLineLength = maxInitialLineLength;
        this.maxHeaderSize = maxHeaderSize;
        this.maxChunkSize = maxChunkSize;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();

        if (sslContext != null) {
            p.addLast(sslContext.newHandler(ch.alloc()));
            p.addLast(NettyHttpServer.SslExceptionHandler.INSTANCE);
            if (http2Enabled) {
                p.addLast(new Http2OrHttp1Handler(maxContentLength, readTimeout, supportMultipart, httpHandler, beforeAggregatorHandlers, afterAggregatorHandlers, maxInitialLineLength, maxHeaderSize, maxChunkSize));
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
        if (readTimeout > 0) {
            p.addLast(new ReadTimeoutHandler(readTimeout, TimeUnit.MILLISECONDS));
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
        p.addLast(httpHandler);
    }

    private static final byte[] H2_PREFACE_BYTES =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    private void addCleartextHttp2Handlers(ChannelPipeline p) {
        // h2c prior knowledge: use a preface detector that switches to HTTP/2
        // when it detects the "PRI * HTTP/2.0" preface, or falls through to HTTP/1.1.
        HttpServerCodec sourceCodec = new HttpServerCodec(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        Http2FrameCodec frameCodec = Http2FrameCodecBuilder.forServer().build();
        Http2MultiplexHandler multiplexHandler = new Http2MultiplexHandler(
                new Http2ChildChannelInitializer(maxContentLength, supportMultipart, httpHandler));

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
                accumulator.writeBytes(buf);

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
        if (readTimeout > 0) {
            p.addLast(new ReadTimeoutHandler(readTimeout, TimeUnit.MILLISECONDS));
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
        p.addLast(httpHandler);
    }

    private void addAggregator(ChannelPipeline p) {
        if (supportMultipart) {
            p.addLast(new SupportMultipartAggregator(maxContentLength));
        } else {
            p.addLast(new HttpObjectAggregator(maxContentLength));
        }
    }

    /**
     * HTTP/2 child channel initializer.
     * Each HTTP/2 stream gets its own child channel with pipeline:
     * Http2StreamFrameToHttpObjectCodec -> ChunkedWriteHandler
     *   -> SupportMultipartAggregator(or HttpObjectAggregator)
     *   -> BackpressureHandler -> NettyHttpHandler
     */
    private static class Http2ChildChannelInitializer extends ChannelInitializer<Channel> {
        private final int maxContentLength;
        private final boolean supportMultipart;
        private final NettyHttpHandler httpHandler;

        Http2ChildChannelInitializer(int maxContentLength, boolean supportMultipart,
                                      NettyHttpHandler httpHandler) {
            this.maxContentLength = maxContentLength;
            this.supportMultipart = supportMultipart;
            this.httpHandler = httpHandler;
        }

        @Override
        protected void initChannel(Channel ch) {
            ChannelPipeline p = ch.pipeline();
            // true = server side: incoming headers -> HttpRequest, outgoing HttpResponse -> headers
            p.addLast(new Http2StreamFrameToHttpObjectCodec(true));
            p.addLast(new ChunkedWriteHandler());
            if (supportMultipart) {
                p.addLast(new SupportMultipartAggregator(maxContentLength));
            } else {
                p.addLast(new HttpObjectAggregator(maxContentLength));
            }
            p.addLast(BackpressureHandler.INSTANCE);
            p.addLast(httpHandler);
        }
    }

    /**
     * ALPN protocol negotiation handler.
     * After TLS handshake, selects h2 or h1.1 pipeline based on ALPN result.
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

        Http2OrHttp1Handler(int maxContentLength, long readTimeout, boolean supportMultipart,
                            NettyHttpHandler httpHandler,
                            List<ChannelHandler> beforeAggregatorHandlers,
                            List<ChannelHandler> afterAggregatorHandlers,
                            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
            super(ApplicationProtocolNames.HTTP_1_1);
            this.maxContentLength = maxContentLength;
            this.readTimeout = readTimeout;
            this.supportMultipart = supportMultipart;
            this.httpHandler = httpHandler;
            this.beforeAggregatorHandlers = beforeAggregatorHandlers != null
                    ? beforeAggregatorHandlers : Collections.emptyList();
            this.afterAggregatorHandlers = afterAggregatorHandlers != null
                    ? afterAggregatorHandlers : Collections.emptyList();
            this.maxInitialLineLength = maxInitialLineLength;
            this.maxHeaderSize = maxHeaderSize;
            this.maxChunkSize = maxChunkSize;
        }

        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
            ChannelPipeline p = ctx.pipeline();
            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                // h2: remove SslExceptionHandler (h1.1 specific), add h2 pipeline
                p.remove(NettyHttpServer.SslExceptionHandler.class);
                p.addLast(Http2FrameCodecBuilder.forServer().build());
                p.addLast(new Http2MultiplexHandler(
                        new Http2ChildChannelInitializer(maxContentLength, supportMultipart, httpHandler)));
            } else {
                // http/1.1: add standard h1.1 pipeline
                p.addLast(new HttpServerCodec(maxInitialLineLength, maxHeaderSize, maxChunkSize));
                if (readTimeout > 0) {
                    p.addLast(new ReadTimeoutHandler(readTimeout, TimeUnit.MILLISECONDS));
                }
                p.addLast(new ChunkedWriteHandler());
                for (ChannelHandler h : beforeAggregatorHandlers) {
                    p.addLast(h);
                }
                if (supportMultipart) {
                    p.addLast(new SupportMultipartAggregator(maxContentLength));
                } else {
                    p.addLast(new HttpObjectAggregator(maxContentLength));
                }
                for (ChannelHandler h : afterAggregatorHandlers) {
                    p.addLast(h);
                }
                p.addLast(BackpressureHandler.INSTANCE);
                p.addLast(httpHandler);
            }
        }
    }
}