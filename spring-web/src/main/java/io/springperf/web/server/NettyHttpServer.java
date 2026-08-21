package io.springperf.web.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContext;
import io.springperf.web.context.LifecycleWebComponent;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.Ordered;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class NettyHttpServer implements SmartLifecycle, LifecycleWebComponent {

    private volatile boolean running = false;

    private final WebContext webContext;
    private final SslContext sslContext;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private NettyHttpHandler httpHandler;
    private boolean http2Enabled;
    private volatile int actualPort;
    private final PipelineCustomizer pipelineCustomizer;

    public NettyHttpServer(WebContext webContext) {
        this(webContext, null, null);
    }

    public NettyHttpServer(WebContext webContext, SslContext sslContext) {
        this(webContext, sslContext, null);
    }

    public NettyHttpServer(WebContext webContext, SslContext sslContext, PipelineCustomizer pipelineCustomizer) {
        this.webContext = webContext;
        this.sslContext = sslContext;
        this.pipelineCustomizer = pipelineCustomizer;
    }

    @Override
    public void start() {
        // 在 Netty 启动前触发 WebContext 生命周期，确保所有 WebComponent 已完成初始化
        webContext.startLifecycle();
        this.http2Enabled = webContext.getProps().getBoolean(PropertiesConstant.HTTP2_ENABLED, false);
        // 在启动阶段（单线程、Netty 未接受连接前）获取 DispatcherHandler
        // 确保 NettyHttpHandler 运行时只需做纯读操作，线程安全
        DispatcherHandler dispatcher = webContext.getWebComponent(DispatcherHandler.class);
        this.httpHandler = new NettyHttpHandler(webContext, webContext.getContextPath(), dispatcher);
        int port = webContext.getProps().getInt(PropertiesConstant.SERVER_PORT);
        int bossThreads = webContext.getProps().getInt(PropertiesConstant.SERVER_NETTY_BOSS_THREADS);
        bossGroup = new NioEventLoopGroup(bossThreads);
        int workerThreads = webContext.getProps().getInt(PropertiesConstant.SERVER_NETTY_WORKERS);
        workerGroup = workerThreads > 0 ? new NioEventLoopGroup(workerThreads) : new NioEventLoopGroup();
        // 收集模块注入的额外 ChannelHandler（如 WebSocket 握手处理器）
        List<ChannelHandler> beforeAggHandlers = pipelineCustomizer != null
                ? pipelineCustomizer.getBeforeAggregatorHandlers() : Collections.emptyList();
        List<ChannelHandler> afterAggHandlers = pipelineCustomizer != null
                ? pipelineCustomizer.getAfterAggregatorHandlers() : Collections.emptyList();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG,
                        webContext.getProps().getInt(PropertiesConstant.SERVER_NETTY_SO_BACKLOG))
                .childOption(ChannelOption.TCP_NODELAY,
                        webContext.getProps().getBoolean(PropertiesConstant.SERVER_NETTY_TCP_NODELAY, PropertiesConstant.SERVER_NETTY_TCP_NODELAY_DEFAULT))
                .childOption(ChannelOption.SO_KEEPALIVE,
                        webContext.getProps().getBoolean(PropertiesConstant.SERVER_NETTY_SO_KEEPALIVE, PropertiesConstant.SERVER_NETTY_SO_KEEPALIVE_DEFAULT))
                .childOption(ChannelOption.SO_REUSEADDR,
                        webContext.getProps().getBoolean(PropertiesConstant.SERVER_NETTY_SO_REUSEADDR, PropertiesConstant.SERVER_NETTY_SO_REUSEADDR_DEFAULT))
                .childOption(ChannelOption.ALLOCATOR,
                        resolveAllocator(webContext.getProps().get(PropertiesConstant.SERVER_NETTY_ALLOCATOR_TYPE, PropertiesConstant.SERVER_NETTY_ALLOCATOR_TYPE_DEFAULT)))
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(
                                webContext.getProps().getInt(PropertiesConstant.WRITE_BUFFER_LOW_WATERMARK),
                                webContext.getProps().getInt(PropertiesConstant.WRITE_BUFFER_HIGH_WATERMARK)
                        ))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        Http2ChannelInitializer innerInit = new Http2ChannelInitializer(
                        http2Enabled,
                        sslContext,
                        webContext.getProps().getInt(PropertiesConstant.HTTP_MAX_CONTENT_LENGTH),
                        webContext.getProps().getLong(PropertiesConstant.HTTP_READ_TIMEOUT),
                        true, // supportMultipart = true for main server
                        httpHandler,
                        beforeAggHandlers,
                        afterAggHandlers,
                        webContext.getProps().getInt(PropertiesConstant.HTTP_MAX_INITIAL_LINE_LENGTH),
                        webContext.getProps().getInt(PropertiesConstant.HTTP_MAX_HEADER_SIZE),
                        webContext.getProps().getInt(PropertiesConstant.HTTP_MAX_CHUNK_SIZE)
                );
                        ch.pipeline().addLast(NettyMetricsHandler.INSTANCE);
                        innerInit.initChannel(ch);
                    }
                });
        try {
            serverChannel = bootstrap.bind(port).sync().channel();
            this.actualPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
            running = true;
	    if (webContext.getCtx() instanceof ConfigurableApplicationContext) {
	        ((ConfigurableApplicationContext) webContext.getCtx())
	                .getEnvironment().getSystemProperties()
	                .put("local.server.port", String.valueOf(this.actualPort));
	    }
            log.info("Netty Server started on port {}", this.actualPort);
        } catch (Exception e) {
            // 绑定失败时及时清理 EventLoopGroup，否则线程残留会阻止 JVM 退出
            if (bossGroup != null) {
                bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            }
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

            // EventLoop 关闭已移至 destroyComponent()，在 BizPoolRegistry 等业务组件排空后执行
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

    /**
     * 返回 Netty 实际绑定的端口。
     * 在 start() 之前调用返回 0，绑定后返回实际端口（可能不同于配置值，如随机端口）。
     */
    public int getActualPort() {
        return actualPort;
    }

    /**
     * Returns the current number of active TCP connections tracked by
     * {@link NettyMetricsHandler}. This includes connections from both the
     * main server and the management server (if configured).
     */
    public int getActiveConnectionCount() {
        return NettyMetricsHandler.INSTANCE.getActiveConnectionCount();
    }

    /**
     * Returns the worker {@link EventLoopGroup} used for processing I/O events.
     * Exposed for metrics registration (e.g., pending tasks gauge).
     */
    public EventLoopGroup getWorkerGroup() {
        return workerGroup;
    }

    /**
     * Resolve ByteBufAllocator by type name.
     * @param type "pooled" (default) or "unpooled"
     */
    private static ByteBufAllocator resolveAllocator(String type) {
        return "unpooled".equalsIgnoreCase(type)
                ? UnpooledByteBufAllocator.DEFAULT
                : PooledByteBufAllocator.DEFAULT;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void destroyComponent() throws Exception {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().sync();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().sync();
        }
        log.info("Netty Server EventLoop shut down");
    }
}