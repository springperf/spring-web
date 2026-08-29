package io.springperf.web.server;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NettyMetricsHandler} 测试：
 * 每个服务器持有独立实例（主/管理服务器计数分离），同一实例可在多 channel 间共享（Sharable）。
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
}
