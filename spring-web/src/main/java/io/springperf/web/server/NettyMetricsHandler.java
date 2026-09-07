package io.springperf.web.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Netty pipeline handler that tracks active TCP connection count.
 * <p>
 * {@link ChannelHandler.Sharable}：可在同一服务器 pipeline 的多个 channel 间共享。
 * 每个服务器持有独立实例（主服务器与管理服务器各自计数），避免连接数跨服务器混算。
 * 当置于 pipeline 头部时，在 {@link #channelActive}/{@link #channelInactive} 上原子增减计数。
 * </p>
 *
 * @since 2.7.0
 */
@ChannelHandler.Sharable
public class NettyMetricsHandler extends ChannelInboundHandlerAdapter {

    private final AtomicInteger activeConnections = new AtomicInteger();

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        activeConnections.incrementAndGet();
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