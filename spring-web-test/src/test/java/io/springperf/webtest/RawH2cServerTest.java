package io.springperf.webtest;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minimal raw Netty h2c server test — no Spring Boot, no framework.
 */
public class RawH2cServerTest {

    private static final byte[] H2_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void testRawH2c() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        int port = 9199;

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            final ChannelPipeline p = ch.pipeline();
                            final Http2FrameCodec frameCodec = Http2FrameCodecBuilder.forServer().build();
                            final Http2MultiplexHandler multiplexHandler = new Http2MultiplexHandler(
                                    new ChannelInitializer<Channel>() {
                                        @Override
                                        protected void initChannel(Channel ch) {
                                            ch.pipeline().addLast(
                                                    new Http2StreamFrameToHttpObjectCodec(true),
                                                    new ChunkedWriteHandler(),
                                                    new HttpObjectAggregator(1048576),
                                                    new SimpleChannelInboundHandler<FullHttpRequest>() {
                                                        @Override
                                                        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                                                            System.err.println("H2-ECHO: " + req.method() + " " + req.uri());
                                                            FullHttpResponse resp = new DefaultFullHttpResponse(
                                                                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                                                    ctx.alloc().buffer().writeBytes("hello".getBytes()));
                                                            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                                                            resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes());
                                                            ctx.writeAndFlush(resp);
                                                        }
                                                    }
                                            );
                                        }
                                    });

                            p.addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    if (msg instanceof ByteBuf) {
                                        ByteBuf buf = (ByteBuf) msg;
                                        if (buf.readableBytes() >= 24) {
                                            boolean match = true;
                                            for (int i = 0; i < 24; i++) {
                                                if (buf.getByte(i) != H2_PREFACE[i]) { match = false; break; }
                                            }
                                            if (match) {
                                                ChannelPipeline pipe = ctx.pipeline();
                                                pipe.addBefore(pipe.context(this).name(), "fc", frameCodec);
                                                pipe.addAfter("fc", "mh", multiplexHandler);
                                                pipe.remove(this);
                                                pipe.fireChannelRead(msg);
                                                return;
                                            }
                                        }
                                    }
                                    ctx.pipeline().remove((ChannelHandler) ctx.handler());
                                    ctx.pipeline().fireChannelRead(msg);
                                }
                            });

                            p.addLast(new HttpServerCodec());
                            p.addLast(new ChunkedWriteHandler());
                            p.addLast(new HttpObjectAggregator(1048576));
                            p.addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                                    System.out.println("HTTP/1.1 FALLBACK: " + req.uri());
                                    FullHttpResponse resp = new DefaultFullHttpResponse(
                                            HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                            ctx.alloc().buffer().writeBytes("http11".getBytes()));
                                    resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                                    resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes());
                                    ctx.writeAndFlush(resp);
                                }
                            });
                        }
                    });

            Channel serverChannel = bootstrap.bind(port).sync().channel();

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .readTimeout(Duration.ofSeconds(10))
                    .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
                    .build();

            Request request = new Request.Builder()
                    .url("http://localhost:" + port + "/test")
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                System.out.println("Status: " + response.code() + " Body: " + response.body().string());
                assertTrue(response.isSuccessful());
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
            }

            serverChannel.close().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
