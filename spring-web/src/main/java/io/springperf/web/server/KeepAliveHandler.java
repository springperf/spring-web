package io.springperf.web.server;

import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * keep-alive 调优 handler（duplex，<b>必须位于 {@code NettyHttpHandler} 之前</b>）：
 * <p>
 * 管线位置是正确性前提——{@code NettyHttpHandler} 是入站终端（不转发 channelRead）且用自身 {@code ctx.writeAndFlush} 写响应（出站只流经其之前的
 * handler）。若置于其后，本 handler 收不到任何事件，计数与空闲超时全部失效（曾因此回归，见管线顺序测试与 keep-alive E2E）。
 * </p>
 * <ul>
 * <li>{@code max-keep-alive-requests}：对每个入站 {@link HttpRequest} 计数，达到上限后于当前响应结束关闭连接 （在响应头写入
 * {@code Connection: close}，flush 完成后关闭）。</li>
 * <li>{@code keep-alive-timeout}：每次响应发送完毕后，若启用则调度空闲超时；超时且无新请求则关闭连接； 新请求到达会取消该调度。</li>
 * </ul>
 * 所有状态（requestCount / idleFuture / closeAfterResponse）仅在所属 EventLoop 线程上访问， 事件处理与定时回调均在同一 EventLoop 串行，无并发问题。
 */
public final class KeepAliveHandler extends ChannelDuplexHandler {

    private final long keepAliveTimeoutMillis;
    private final int maxKeepAliveRequests;

    private int requestCount;
    private boolean closeAfterResponse;
    private ScheduledFuture<?> idleFuture;

    public KeepAliveHandler(long keepAliveTimeoutMillis, int maxKeepAliveRequests) {
        this.keepAliveTimeoutMillis = keepAliveTimeoutMillis;
        this.maxKeepAliveRequests = maxKeepAliveRequests;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            // 新请求到来：取消上一次响应结束后的空闲关闭计时（连接仍活跃）
            cancelIdle();
            requestCount++;
            if (maxKeepAliveRequests > 0 && requestCount >= maxKeepAliveRequests) {
                // 当前响应处理完后关闭连接（对齐 Tomcat：服务满 max 个请求后断开）
                closeAfterResponse = true;
            }
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) msg;
            if (closeAfterResponse) {
                // 必须【无条件覆盖】：响应层（NettyServerHttpResponse）会按 keepAlive 状态显式写
                // Connection: keep-alive，若仅在缺失时补写会被已存在的 keep-alive 挡住，
                // 客户端将误信连接可复用（曾因此回归，见 KeepAliveE2eTest）。
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            }
        }
        if (msg instanceof LastHttpContent) {
            if (closeAfterResponse) {
                // 当前响应是最后一个：写完即关闭连接
                promise.addListener(f -> ctx.close());
            } else if (keepAliveTimeoutMillis > 0) {
                scheduleIdle(ctx);
            }
        }
        super.write(ctx, msg, promise);
    }

    private void scheduleIdle(ChannelHandlerContext ctx) {
        cancelIdle();
        idleFuture = ctx.executor().schedule(() -> ctx.close(), keepAliveTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelIdle() {
        if (idleFuture != null) {
            idleFuture.cancel(false);
            idleFuture = null;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelIdle();
        super.channelInactive(ctx);
    }
}
