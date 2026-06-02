package io.springperf.web.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ChannelHandler.Sharable
public final class BackpressureHandler extends ChannelInboundHandlerAdapter {

    public static final BackpressureHandler INSTANCE = new BackpressureHandler();

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        boolean writable = ch.isWritable();

        ConnectionContext conn = ch.attr(NettyServerHttpResponse.CONN_CTX).get();
        if (conn == null) {
            ctx.fireChannelWritabilityChanged();
            return;
        }

        boolean last = conn.lastWritable();

        // 只处理 false -> true
        if (!last && writable) {
            Runnable cb = conn.getOnWritable();
            if (cb != null) {
                log.info("onWritable ,channel:{}", ch);
                cb.run();
            }
        }
        conn.updateWritable(writable);
        ctx.fireChannelWritabilityChanged();
    }
}