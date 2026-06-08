package io.springperf.web.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.Future;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.filter.WebFilterRegistry;
import io.springperf.web.http.BackpressureHandler;
import io.springperf.web.http.support.SupportMultipartAggregator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.TimeUnit;

@Slf4j
public class NettyHttpServer implements SmartLifecycle {

    private volatile boolean running = false;

    private final WebContext webContext;
    private final SslContext sslContext;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private NettyHttpHandler httpHandler;

    public NettyHttpServer(WebContext webContext) {
        this(webContext, null);
    }

    public NettyHttpServer(WebContext webContext, SslContext sslContext) {
        this.webContext = webContext;
        this.sslContext = sslContext;
    }

    @Override
    public void start() {
        // 在 Netty 启动前触发 WebContext 生命周期，确保所有 WebComponent 已完成初始化
        webContext.startLifecycle();
        // 在启动阶段（单线程、Netty 未接受连接前）注册 WebFilterRegistry
        // 确保 NettyHttpHandler 运行时只需做纯读操作，线程安全
        WebFilterRegistry registry = webContext.getWebComponentWithDefault(WebFilterRegistry.class, new WebFilterRegistry());
        this.httpHandler = new NettyHttpHandler(webContext, webContext.getContextPath(), registry);
        int port = webContext.getProps().getInt(PropertiesConstant.SERVER_PORT, 8080);
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ChannelPipeline p = ch.pipeline();
                if (sslContext != null) {
                    p.addLast(sslContext.newHandler(ch.alloc()));
                    p.addLast(SslExceptionHandler.INSTANCE);
                }
                p.addLast(new HttpServerCodec());
                p.addLast(new ChunkedWriteHandler());
                p.addLast(new SupportMultipartAggregator(webContext.getProps().getInt(PropertiesConstant.HTTP_MAX_CONTENT_LENGTH, 1048576)));
                p.addLast(BackpressureHandler.INSTANCE);
                p.addLast(httpHandler);
            }
        });
        try {
            serverChannel = bootstrap.bind(port).sync().channel();
            running = true;
            log.info("Netty Server started on port {}", port);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start Netty", e);
        }
    }

    @ChannelHandler.Sharable
    public static class SslExceptionHandler extends io.netty.channel.ChannelInboundHandlerAdapter {
        public static final SslExceptionHandler INSTANCE = new SslExceptionHandler();

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof NotSslRecordException || (cause instanceof io.netty.handler.codec.DecoderException
                    && cause.getCause() instanceof NotSslRecordException)) {
                // HTTP 请求打到 HTTPS 端口，预期行为，静默关闭
                ctx.close();
                return;
            }
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            // 1. 通知 handler 拒绝新请求（503 Service Unavailable）
            httpHandler.setShuttingDown();

            // 2. 停止接受新连接
            if (serverChannel != null) {
                serverChannel.close().sync();
            }

            // 3. 等待业务线程池排空（此时 EventLoop 仍在运行，BizPool 可正常写响应）
            BizPoolRegistry bizPoolRegistry = webContext.getWebComponent(BizPoolRegistry.class);
            if (bizPoolRegistry != null) {
                int timeout = webContext.getProps().getInt(PropertiesConstant.SERVER_SHUTDOWN_TIMEOUT, 30000);
                bizPoolRegistry.shutdownPools(timeout, TimeUnit.MILLISECONDS);
            }

            // 4. 优雅关闭事件循环组
            Future<?> bossFuture = bossGroup != null ? bossGroup.shutdownGracefully() : null;
            Future<?> workerFuture = workerGroup != null ? workerGroup.shutdownGracefully() : null;

            if (bossFuture != null) {
                bossFuture.sync();
            }
            if (workerFuture != null) {
                workerFuture.sync();
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            running = false;
            callback.run(); // 告诉 Spring 优雅关机完成
        }
    }

    @Override
    public void stop() {
        stop(() -> {
        });
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}