package io.springperf.web.autoconfigure.actuator.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.Future;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.server.Http2ChannelInitializer;
import io.springperf.web.server.HttpHandler;
import io.springperf.web.server.NettyHttpHandler;
import io.springperf.web.server.NettyMetricsHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

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
    private boolean http2Enabled;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private NettyHttpHandler nettyHttpHandler;

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
        this.http2Enabled = webContext.getProps().getBoolean(PropertiesConstant.HTTP2_ENABLED, false);
        // 触发 WebContext 生命周期（WebComponent 初始化），AtomicBoolean 保证幂等
        webContext.startLifecycle();

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        NettyHttpHandler nettyHttpHandler = new NettyHttpHandler(webContext, "", handler);
        this.nettyHttpHandler = nettyHttpHandler;

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        Http2ChannelInitializer innerInit = new Http2ChannelInitializer(
                                http2Enabled,
                                sslContext,
                                maxContentLength,
                                webContext.getProps().getLong(PropertiesConstant.HTTP_READ_TIMEOUT),
                                false, // supportMultipart = false (management port uses HttpObjectAggregator)
                                nettyHttpHandler,
                                Collections.emptyList(),
                                Collections.emptyList(),
                                webContext.getProps().getInt(PropertiesConstant.HTTP_MAX_INITIAL_LINE_LENGTH),
                                webContext.getProps().getInt(PropertiesConstant.HTTP_MAX_HEADER_SIZE),
                                webContext.getProps().getInt(PropertiesConstant.HTTP_MAX_CHUNK_SIZE)
                        );
                        ch.pipeline().addLast(NettyMetricsHandler.INSTANCE);
                        ch.pipeline().addLast(innerInit);
                    }
                });

        try {
            serverChannel = bootstrap.bind(port).sync().channel();
            running = true;
            log.info("Management server started on port {} (actuator only)", port);
        } catch (Exception e) {
            // 绑定失败时及时清理 EventLoopGroup，否则线程残留会阻止 JVM 退出
            if (bossGroup != null) {
                bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            }
            throw new IllegalStateException("Failed to start management server on port " + port, e);
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            // 1. 拒绝新请求（503 Service Unavailable）
            if (nettyHttpHandler != null) {
                nettyHttpHandler.setShuttingDown();
            }

            // 2. 停止接受新连接
            if (serverChannel != null) {
                serverChannel.close().sync();
            }

            // 3. 优雅关闭事件循环组
            Future<?> bossFuture = bossGroup != null ? bossGroup.shutdownGracefully() : null;
            Future<?> workerFuture = workerGroup != null ? workerGroup.shutdownGracefully() : null;

            if (bossFuture != null) {
                bossFuture.sync();
            }
            if (workerFuture != null) {
                workerFuture.sync();
            }
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