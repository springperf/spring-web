package io.springperf.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * {@link NettyMetricsHandler} 测试： 每个服务器持有独立实例（主/管理服务器计数分离），同一实例可在多 channel 间共享（Sharable）。
 */
class NettyMetricsHandlerTest {

    @Test
    void activeCount_tracksChannelLifecycle() {
        NettyMetricsHandler handler = new NettyMetricsHandler();

        EmbeddedChannel channel = new EmbeddedChannel(handler);
        assertEquals(1, handler.getActiveConnectionCount());
        channel.close();
        assertEquals(0, handler.getActiveConnectionCount());
    }

    @Test
    void distinctInstances_countIndependently() {
        NettyMetricsHandler main = new NettyMetricsHandler();
        NettyMetricsHandler management = new NettyMetricsHandler();

        EmbeddedChannel mainChannel = new EmbeddedChannel(main);
        EmbeddedChannel mgmtChannel = new EmbeddedChannel(management);
        EmbeddedChannel mgmtChannel2 = new EmbeddedChannel(management);

        assertEquals(1, main.getActiveConnectionCount(), "main server counts only its own connections");
        assertEquals(2, management.getActiveConnectionCount(), "management server counts its own connections");

        mainChannel.close();
        mgmtChannel.close();
        mgmtChannel2.close();
    }

    @Test
    void channelActive_rejectsWhenOverMaxConnections() {
        NettyMetricsHandler handler = new NettyMetricsHandler(2);
        ChannelHandlerContext c1 = mockCtx();
        handler.channelActive(c1);
        verify(c1).fireChannelActive();
        verify(c1, never()).close();

        ChannelHandlerContext c2 = mockCtx();
        handler.channelActive(c2);
        verify(c2).fireChannelActive();
        verify(c2, never()).close();

        // 第 3 个连接超阈值：直接关闭且不向下游 fireChannelActive
        ChannelHandlerContext c3 = mockCtx();
        handler.channelActive(c3);
        verify(c3).close();
        verify(c3, never()).fireChannelActive();
    }

    @Test
    void channelActive_zeroMaxMeansUnlimited() {
        NettyMetricsHandler handler = new NettyMetricsHandler(0);
        for (int i = 0; i < 5; i++) {
            ChannelHandlerContext c = mockCtx();
            handler.channelActive(c);
            verify(c).fireChannelActive();
            verify(c, never()).close();
        }
    }

    private static ChannelHandlerContext mockCtx() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(mock(Channel.class));
        return ctx;
    }
}
