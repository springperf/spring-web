package io.springperf.web.autoconfigure.actuator.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.Future;
import io.springperf.web.context.WebContext;
import io.springperf.web.server.HttpHandler;
import io.springperf.web.server.NettyHttpHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * 管理端口 Netty 服务器。
 * <p>当 {@code management.server.port} 配置且与 {@code server.port} 不同时，
 * 启动第二个 Netty 服务器仅用于 Actuator 端点。</p>
 * <p>支持通过 {@code management.server.ssl.*} 配置 SSL/TLS。</p>
 * <p>实现 {@link SmartLifecycle}，{@link #getPhase()} 返回
 * {@link Integer#MAX_VALUE} 与主服务器一致，确保在 Spring 上下文就绪后启动。</p>
 */
@Slf4j
public class ManagementNettyHttpServer implements SmartLifecycle {

    private volatile boolean running = false;

    private final WebContext webContext;
    private final String contextPath;
    private final HttpHandler handler;
    private final int port;
    private final int maxContentLength;
    private final SslContext sslContext;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public ManagementNettyHttpServer(WebContext webContext, String contextPath, HttpHandler handler,
                                     int port, int maxContentLength) {
        this(webContext, contextPath, handler, port, maxContentLength, null);
    }

    public ManagementNettyHttpServer(WebContext webContext, String contextPath, HttpHandler handler,
                                     int port, int maxContentLength, SslContext sslContext) {
        this.webContext = webContext;
        this.contextPath = contextPath;
        this.handler = handler;
        this.port = port;
        this.maxContentLength = maxContentLength;
        this.sslContext = sslContext;
    }

    @Override
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        NettyHttpHandler nettyHttpHandler = new NettyHttpHandler(webContext, "", handler);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        if (sslContext != null) {
                            p.addLast(sslContext.newHandler(ch.alloc()));
                            p.addLast(io.springperf.web.server.NettyHttpServer.SslExceptionHandler.INSTANCE);
                        }
                        p.addLast(new HttpServerCodec());
                        p.addLast(new ChunkedWriteHandler());
                        p.addLast(new HttpObjectAggregator(maxContentLength));
                        p.addLast(nettyHttpHandler);
                    }
                });

        try {
            serverChannel = bootstrap.bind(port).sync().channel();
            running = true;
            log.info("Management server started on port {} (actuator only)", port);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start management server on port " + port, e);
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            serverChannel.close().sync();
            Future<?> bossFuture = bossGroup.shutdownGracefully();
            Future<?> workerFuture = workerGroup.shutdownGracefully();
            bossFuture.sync();
            workerFuture.sync();
        } catch (Exception e) {
            log.error("Management server shutdown error", e);
        } finally {
            running = false;
            callback.run();
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