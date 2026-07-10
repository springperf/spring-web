package io.springperf.web.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Netty pipeline handler that tracks active TCP connection count.
 * <p>
 * Designed as a {@link ChannelHandler.Sharable} singleton (see {@link #INSTANCE}),
 * following the same pattern as {@link io.springperf.web.http.BackpressureHandler}.
 * When placed at the head of the pipeline, it atomically increments/decrements
 * a counter on {@link #channelActive}/{@link #channelInactive} events.
 * </p>
 *
 * @since 2.7.0
 */
@ChannelHandler.Sharable
public class NettyMetricsHandler extends ChannelInboundHandlerAdapter {

    public static final NettyMetricsHandler INSTANCE = new NettyMetricsHandler();

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