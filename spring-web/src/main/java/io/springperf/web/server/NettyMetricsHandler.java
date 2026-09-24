package io.springperf.web.server;

import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Netty pipeline handler that tracks active TCP connection count.
 * <p>
 * {@link ChannelHandler.Sharable}：可在同一服务器 pipeline 的多个 channel 间共享。 每个服务器持有独立实例（主服务器与管理服务器各自计数），避免连接数跨服务器混算。 当置于
 * pipeline 头部时，在 {@link #channelActive}/{@link #channelInactive} 上原子增减计数。
 * </p>
 *
 * @since 2.7.0
 */
@ChannelHandler.Sharable
public class NettyMetricsHandler extends ChannelInboundHandlerAdapter {

    private final AtomicInteger activeConnections = new AtomicInteger();
    /** 最大并发连接数（来自 {@code server.max-connections}，≤0 表示不限制，默认 0）。 */
    private volatile int maxConnections = 0;

    public NettyMetricsHandler() {
    }

    public NettyMetricsHandler(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    /**
     * 注入最大连接数（≤0 表示不限制）。在服务器 {@code start()} 阶段调用一次。
     */
    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        int count = activeConnections.incrementAndGet();
        // 超阈值：直接关闭 TCP 连接（此时尚未解析 HTTP，无法返回 503），
        // 对齐 Tomcat accept 队列满的拒绝语义；不 fireChannelActive，
        // 由即将触发的 channelInactive 完成计数回退，避免计数泄漏。
        if (maxConnections > 0 && count > maxConnections) {
            ctx.close();
            return;
        }
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        activeConnections.decrementAndGet();
        ctx.fireChannelInactive();
    }

    public int getActiveConnectionCount() {
        return activeConnections.get();
    }
}
