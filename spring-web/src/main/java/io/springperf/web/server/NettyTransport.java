package io.springperf.web.server;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Netty transport 工厂：根据 {@code server.netty.transport} 配置选择 EventLoopGroup / ServerSocketChannel 实现。
 * <ul>
 *   <li>{@code auto}（默认）：Linux 上 epoll 可用时使用 native epoll，否则回退 Java NIO（Windows/macOS）</li>
 *   <li>{@code nio}：强制 Java NIO</li>
 *   <li>{@code epoll}：强制 native epoll，不可用（如非 Linux 平台）时启动失败</li>
 * </ul>
 * <p>依赖：框架按具体 netty 模块依赖（不用 netty-all）。epoll 支持依赖
 * {@code netty-transport-classes-epoll}（编译期类）与 {@code netty-transport-native-epoll}
 * 及其 linux-x86_64/linux-aarch_64/linux-riscv64 native classifier（运行期，仅匹配平台加载）。</p>
 */
public final class NettyTransport {

    public static final String MODE_AUTO = "auto";
    public static final String MODE_NIO = "nio";
    public static final String MODE_EPOLL = "epoll";

    private NettyTransport() {
    }

    /**
     * epoll native transport 是否可用（Linux + native 库已加载）。
     */
    public static boolean isEpollAvailable() {
        try {
            return Epoll.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按模式解析 epoll 是否被启用。auto 模式跟随平台可用性。
     *
     * @throws IllegalArgumentException 非法模式值
     * @throws IllegalStateException    强制 epoll 但平台不支持
     */
    public static boolean useEpoll(String mode) {
        if (mode == null || mode.isEmpty()) {
            mode = MODE_AUTO;
        }
        String normalized = mode.trim().toLowerCase();
        switch (normalized) {
            case MODE_AUTO:
                return isEpollAvailable();
            case MODE_NIO:
                return false;
            case MODE_EPOLL:
                if (!isEpollAvailable()) {
                    throw new IllegalStateException(
                            "server.netty.transport=epoll but native epoll transport is not available "
                                    + "(requires Linux with netty-transport-native-epoll loaded)");
                }
                return true;
            default:
                throw new IllegalArgumentException(
                        "Unsupported server.netty.transport value: '" + mode + "' (expected auto/nio/epoll)");
        }
    }

    /**
     * 创建 boss EventLoopGroup。
     */
    public static EventLoopGroup newBossGroup(int threads, String mode) {
        return useEpoll(mode)
                ? new EpollEventLoopGroup(threads)
                : new NioEventLoopGroup(threads);
    }

    /**
     * 创建 worker EventLoopGroup。threads &lt;= 0 表示使用默认线程数（2 * CPU 核数）。
     */
    public static EventLoopGroup newWorkerGroup(int threads, String mode) {
        if (useEpoll(mode)) {
            return threads > 0 ? new EpollEventLoopGroup(threads) : new EpollEventLoopGroup();
        }
        return threads > 0 ? new NioEventLoopGroup(threads) : new NioEventLoopGroup();
    }

    /**
     * 返回与模式匹配的 ServerSocketChannel 实现类。
     */
    public static Class<? extends ServerChannel> serverChannelClass(String mode) {
        return useEpoll(mode) ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }
}
