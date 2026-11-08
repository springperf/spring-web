package io.springperf.web.server;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * {@link NettyTransport} 测试。
 * <p>
 * 当前测试环境（Windows）epoll 不可用，auto 与 nio 均回退 NIO； 强制 epoll 时应抛 {@link IllegalStateException}。
 * </p>
 */
class NettyTransportTest {

    @Test
    void isEpollAvailable_matchesNettyEpoll() {
        assertEquals(Epoll.isAvailable(), NettyTransport.isEpollAvailable(),
                "isEpollAvailable 必须与 Netty Epoll.isAvailable() 一致");
    }

    @Test
    void useEpoll_auto_followsEpollAvailability() {
        // 原实现在支持 epoll 的平台直接 return，等于零断言；auto 语义应与可用性一致
        assertEquals(NettyTransport.isEpollAvailable(), NettyTransport.useEpoll("auto"), "auto 模式必须跟随 epoll 实际可用性");
    }

    @Test
    void useEpoll_nio_returnsFalseAlways() {
        assertFalse(NettyTransport.useEpoll("nio"));
        assertFalse(NettyTransport.useEpoll("NIO"));
    }

    @Test
    void useEpoll_epoll_matchesAvailability() {
        if (NettyTransport.isEpollAvailable()) {
            assertTrue(NettyTransport.useEpoll("epoll"), "epoll 可用时强制 epoll 应启用");
        } else {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> NettyTransport.useEpoll("epoll"));
            assertTrue(ex.getMessage().contains("epoll"), "异常信息应说明 epoll 不可用");
        }
    }

    @Test
    void useEpoll_invalidMode_throwsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> NettyTransport.useEpoll("tcp"));
        assertTrue(ex.getMessage().contains("auto/nio/epoll"));
    }

    @Test
    void useEpoll_nullOrEmpty_fallsBackToAuto() {
        // null/"" 语义与 auto 一致（NettyTransport.useEpoll 内部统一回退 auto），
        // 断言二者返回结果相同，避免平台差异（Linux auto 为 true，Windows 为 false）
        assertEquals(NettyTransport.useEpoll("auto"), NettyTransport.useEpoll(null));
        assertEquals(NettyTransport.useEpoll("auto"), NettyTransport.useEpoll(""));
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
