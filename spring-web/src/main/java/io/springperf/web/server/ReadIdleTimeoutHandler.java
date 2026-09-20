package io.springperf.web.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 读空闲超时处理器：{@code server.http.read-timeout} 只约束**读空闲**，不得掐断正在处理的请求。
 *
 * <p>Netty 自带的 {@link io.netty.handler.timeout.ReadTimeoutHandler} 在「无读事件」时即触发并关闭连接。
 * 但业务处理期间（慢 SQL、下游调用、异步挂起）本来就没有读事件——直接用它会把这些请求的响应连同连接
 * 一起丢弃：只要处理器耗时超过 {@code read-timeout}（默认 30s），客户端就收不到响应。</p>
 *
 * <p>因此本处理器在计时到期时先检查该连接是否有请求在途（复用
 * {@link NettyHttpHandler} 已有的 pipelining 在途标记），有则重新计时，无则关闭连接。
 * 语义对齐 Tomcat 的 {@code connectionTimeout}：只回收真正的空闲连接与半截请求。</p>
 */
public class ReadIdleTimeoutHandler extends ChannelInboundHandlerAdapter {

    private static final InternalLogger log = InternalLoggerFactory.getInstance(ReadIdleTimeoutHandler.class);

    private final long timeoutMillis;
    private ScheduledFuture<?> timeoutTask;

    public ReadIdleTimeoutHandler(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        reschedule(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        // 每次读事件（含分片到达）都重置空闲计时：慢速但持续的请求不会被误杀
        reschedule(ctx);
        super.channelReadComplete(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelTask();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelTask();
    }

    private void reschedule(ChannelHandlerContext ctx) {
        cancelTask();
        if (!ctx.channel().isActive()) {
            return;
        }
        timeoutTask = ctx.executor().schedule(() -> onTimeout(ctx), timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void onTimeout(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        if (!channel.isActive()) {
            return;
        }
        if (NettyHttpHandler.isRequestInFlight(channel)) {
            // 请求处理中：长时间无读事件属正常，改期再审，不得关闭连接
            if (log.isDebugEnabled()) {
                log.debug("Read idle timeout hit while a request is in flight, rescheduling: {}", channel);
            }
            reschedule(ctx);
            return;
        }
        log.debug("Closing idle connection after {} ms of read inactivity: {}", timeoutMillis, channel);
        ctx.close();
    }

    private void cancelTask() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
    }
}
