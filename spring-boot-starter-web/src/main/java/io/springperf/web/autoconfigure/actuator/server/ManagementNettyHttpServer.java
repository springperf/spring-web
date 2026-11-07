package io.springperf.web.autoconfigure.actuator.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import io.springperf.web.context.LifecycleWebComponent;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.server.CompressionConfig;
import io.springperf.web.server.Http2ChannelInitializer;
import io.springperf.web.server.KeepAliveConfig;
import io.springperf.web.server.ResponseLimitConfig;
import io.springperf.web.server.HttpHandler;
import io.springperf.web.server.NettyHttpHandler;
import io.springperf.web.server.NettyMetricsHandler;
import io.springperf.web.server.NettyTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.Ordered;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 管理端口 Netty 服务器。
 * <p>
 * 当 {@code management.server.port} 配置且与 {@code server.port} 不同时， 启动第二个 Netty 服务器仅用于 Actuator 端点。
 * </p>
 * <p>
 * 所有服务器级配置由 {@link ManagementServerSettings} 在启动期固化：默认继承 {@code server.*}，
 * 白名单内的 {@code management.server.*} 显式覆盖。SSL 与 HTTP/2 同源于该快照。
 * </p>
 * <p>
 * 实现 {@link SmartLifecycle}，{@link #getPhase()} 返回 {@link Integer#MAX_VALUE} 与主服务器一致，确保在 Spring 上下文就绪后启动。
 * </p>
 */
@Slf4j
public class ManagementNettyHttpServer implements SmartLifecycle, LifecycleWebComponent {

    private volatile boolean running = false;

    private final WebContext webContext;
    private final String contextPath;
    private final HttpHandler handler;
    private final int port;
    private final int maxContentLength;
    private final SslContext sslContext;
    private final ManagementServerSettings settings;
    private boolean http2Enabled;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private NettyHttpHandler nettyHttpHandler;
    private volatile int actualPort;
    /** 管理服务器独立的连接计数（与主服务器各自计数，不共享全局单例）。 */
    private final NettyMetricsHandler metricsHandler = new NettyMetricsHandler();
    /** 优雅关闭等待时长（毫秒），来自配置快照，关闭时传入 EventLoopGroup.shutdownGracefully。 */
    private long shutdownGraceMillis = PropertiesConstant.SERVER_SHUTDOWN_GRACE_PERIOD_DEFAULT;

    public ManagementNettyHttpServer(WebContext webContext, String contextPath, HttpHandler handler, int port,
            int maxContentLength) {
        this(webContext, contextPath, handler, port, maxContentLength, null);
    }

    /** 兼容旧调用方：从共享 WebContext 的 ApplicationProperties 构造快照（不做前缀回退）。 */
    public ManagementNettyHttpServer(WebContext webContext, String contextPath, HttpHandler handler, int port,
            int maxContentLength, SslContext sslContext) {
        this(webContext, contextPath, handler, ManagementServerSettings.fromApplicationProperties(
                webContext.getProps(), port, maxContentLength, sslContext));
    }

    public ManagementNettyHttpServer(WebContext webContext, String contextPath, HttpHandler handler,
            ManagementServerSettings settings) {
        this.webContext = webContext;
        this.contextPath = contextPath;
        this.handler = handler;
        this.settings = settings;
        this.port = settings.getPort();
        this.maxContentLength = settings.getMaxContentLength();
        this.sslContext = settings.getSslContext();
        this.shutdownGraceMillis = settings.getShutdownGraceMillis();
    }

    @Override
    public void start() {
        // 与 ALPN 共用快照里的同一个开关，杜绝「协商 h2、管线却是 http/1.1」的撕裂
        this.http2Enabled = settings.getHttp2Enabled();
        // 触发 WebContext 生命周期（WebComponent 初始化），AtomicBoolean 保证幂等
        webContext.startLifecycle();
        metricsHandler.setMaxConnections(settings.getMaxConnections());

        String transportMode = settings.getTransport();
        bossGroup = NettyTransport.newBossGroup(1, transportMode);
        workerGroup = NettyTransport.newWorkerGroup(0, transportMode);

        // 全部来自启动期固化的快照：management.server.* 显式覆盖，否则继承 server.*
        CompressionConfig compressionConfig = settings.getCompression();
        KeepAliveConfig keepAliveConfig = settings.getKeepAlive();
        ResponseLimitConfig responseLimitConfig = settings.getResponseLimit();
        NettyHttpHandler nettyHttpHandler = new NettyHttpHandler(webContext, "", handler, compressionConfig.isEnabled(),
                responseLimitConfig);
        this.nettyHttpHandler = nettyHttpHandler;

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup).channel(NettyTransport.serverChannelClass(transportMode))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        Http2ChannelInitializer innerInit = new Http2ChannelInitializer(http2Enabled, sslContext,
                                maxContentLength, settings.getReadTimeoutMillis(),
                                false, // supportMultipart = false (management port uses HttpObjectAggregator)
                                nettyHttpHandler, Collections.emptyList(), Collections.emptyList(),
                                settings.getMaxInitialLineLength(), settings.getMaxRequestHeaderSize(),
                                settings.getMaxChunkSize(), settings.getMultipartMaxPartCount(),
                                settings.getMultipartMaxPartHeaderSize(), compressionConfig, keepAliveConfig);
                        ch.pipeline().addLast(metricsHandler);
                        ch.pipeline().addLast(innerInit);
                    }
                });

        try {
            String bindAddress = settings.getBindAddress();
            java.net.InetSocketAddress bindSocketAddress = (bindAddress != null && !bindAddress.trim().isEmpty())
                    ? new java.net.InetSocketAddress(bindAddress.trim(), port)
                    : new java.net.InetSocketAddress(port);
            serverChannel = bootstrap.bind(bindSocketAddress).sync().channel();
            this.actualPort = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
            running = true;
            log.info("Management server started on port {} (actuator only)", this.actualPort);
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

            // EventLoop 关闭已移至 destroyComponent()，在 BatchRegistry 等组件排空后执行
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

    /**
     * 返回实际绑定的管理端口。start() 前返回 0，绑定后返回实际端口（可能为随机端口）。
     */
    public int getActualPort() {
        return actualPort;
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
        // 对齐 Spring Boot：quietPeriod=0，最多等待 grace-period 让在途请求排空后强制关闭
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, shutdownGraceMillis, TimeUnit.MILLISECONDS).sync();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, shutdownGraceMillis, TimeUnit.MILLISECONDS).sync();
        }
        log.info("Management server EventLoop shut down (grace-period={}ms)", shutdownGraceMillis);
    }
}
