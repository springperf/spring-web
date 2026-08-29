package io.springperf.web.server;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NettyTransport} 测试。
 * <p>当前测试环境（Windows）epoll 不可用，auto 与 nio 均回退 NIO；
 * 强制 epoll 时应抛 {@link IllegalStateException}。</p>
 */
class NettyTransportTest {

    @Test
    void isEpollAvailable_returnsBooleanWithoutThrowing() {
        // 不关心具体值，只验证不抛异常（native 库缺失时也能安全返回 false）
        boolean result = NettyTransport.isEpollAvailable();
        assertTrue(result == true || result == false);
    }

    @Test
    void useEpoll_auto_onNonEpollPlatform_returnsFalse() {
        if (NettyTransport.isEpollAvailable()) {
            return; // 若平台实际支持 epoll（Linux），auto 应为 true，跳过此断言
        }
        assertFalse(NettyTransport.useEpoll("auto"));
    }

    @Test
    void useEpoll_nio_returnsFalseAlways() {
        assertFalse(NettyTransport.useEpoll("nio"));
        assertFalse(NettyTransport.useEpoll("NIO"));
    }

    @Test
    void useEpoll_epoll_onNonEpollPlatform_throwsIllegalState() {
        if (NettyTransport.isEpollAvailable()) {
            return;
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NettyTransport.useEpoll("epoll"));
        assertTrue(ex.getMessage().contains("epoll"));
    }

    @Test
    void useEpoll_invalidMode_throwsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> NettyTransport.useEpoll("tcp"));
        assertTrue(ex.getMessage().contains("auto/nio/epoll"));
    }

    @Test
    void useEpoll_nullOrEmpty_fallsBackToAuto() {
        assertFalse(NettyTransport.useEpoll(null));
        assertFalse(NettyTransport.useEpoll(""));
    }

    @Test
    void newBossGroup_nio_returnsNioEventLoopGroup() {
        EventLoopGroup group = NettyTransport.newBossGroup(1, "nio");
        assertTrue(group instanceof NioEventLoopGroup);
        group.shutdownGracefully();
    }

    @Test
    void newWorkerGroup_nio_returnsNioEventLoopGroup() {
        EventLoopGroup group = NettyTransport.newWorkerGroup(2, "nio");
        assertTrue(group instanceof NioEventLoopGroup);
        group.shutdownGracefully();
    }

    @Test
    void serverChannelClass_nio_returnsNioServerSocketChannel() {
        assertEquals(NioServerSocketChannel.class, NettyTransport.serverChannelClass("nio"));
    }

    @Test
    void serverChannelClass_epoll_matchesAvailability() {
        // 强制 epoll 成功（Linux）或抛异常（当前平台）——二者都反映正确行为
        if (NettyTransport.isEpollAvailable()) {
            Class<? extends ServerChannel> cls = NettyTransport.serverChannelClass("epoll");
            assertEquals(EpollServerSocketChannel.class, cls);
        } else {
            assertThrows(IllegalStateException.class, () -> NettyTransport.serverChannelClass("epoll"));
        }
    }
}
