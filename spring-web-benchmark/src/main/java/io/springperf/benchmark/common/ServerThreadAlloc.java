package io.springperf.benchmark.common;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务端线程分配量统计器 —— 把「服务端每请求分配字节」从客户端与线程调度噪声中彻底剥离。
 *
 * <h2>为什么不能用 {@code -prof gc}</h2>
 * in-process 模式下客户端（OkHttp）与服务端（Netty）同处一个 JVM，{@code -prof gc} 的
 * {@code gc.alloc.rate.norm} 是<strong>两侧之和</strong>。客户端本身分配量就有 ~6.8 KB/op，
 * 其波动会直接淹没服务端差异。此前实测客户端两版仅差 +0.4%，而进程内总和差 +6.1%，
 * 即差值主体在服务端，却被客户端噪声掩盖。
 *
 * <h2>本类的做法</h2>
 * 用 {@link com.sun.management.ThreadMXBean#getThreadAllocatedBytes} 读指定线程的累计分配字节数。
 * 这是<strong>精确计数</strong>（TLAB 累加），不是采样统计，因此：
 * <ul>
 * <li>不受 CPU 调度、GC 时机、JIT 编译进度影响 —— 这是与吞吐测量的本质区别；</li>
 * <li>误差为零，两个版本的数字可以直接相减；</li>
 * <li>能按线程维度归因，明确区分 EventLoop 与业务池。</li>
 * </ul>
 *
 * <h2>线程集合的发现与缓存</h2>
 * 服务端线程名由 {@code NettyTransport} 决定（{@code epollEventLoopGroup-*} /
 * {@code nioEventLoopGroup-*}），业务池为 {@code pool-*}（{@code BizPoolRegistry}）。
 * 用前缀匹配，跨 NIO/epoll 两种 transport 均适用。
 *
 * <p>
 * 线程 ID <strong>在 {@link #refresh()} 时一次性发现并缓存</strong>：{@code getThreadInfo()}
 * 会分配 {@link ThreadInfo} 对象，若放进每次测量都会污染分配量本身。EventLoop 线程数在
 * 服务启动后即固定（NIO 默认 2×CPU），故缓存后无需重扫；{@link #mark()} 会在发现
 * 已缓存线程全部消失时自动触发一次重扫，防止服务端重启导致统计失效。
 */
public final class ServerThreadAlloc {

    /** Netty EventLoop / 业务池线程名前缀。 */
    private static final String[] SERVER_THREAD_PREFIXES = { "epollEventLoopGroup", "nioEventLoopGroup", "kqueueEventLoopGroup",
            "pool-", "perf-virtual-", "http-nio-" };

    private final com.sun.management.ThreadMXBean threadMx;
    /** 缓存的服务端线程 ID。顺序稳定，便于诊断输出。 */
    private long[] serverThreadIds = new long[0];
    /** 线程名快照，仅用于诊断输出。 */
    private String[] serverThreadNames = new String[0];
    /** 上次累计分配值之和；{@code mark()} 返回相对它的增量。 */
    private long lastTotal;
    /** 已建立有效基线（至少成功读到一次非负累计值）。 */
    private boolean primed;

    public ServerThreadAlloc() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean)) {
            throw new IllegalStateException(
                    "com.sun.management.ThreadMXBean unavailable — cannot isolate server-side allocation");
        }
        this.threadMx = (com.sun.management.ThreadMXBean) bean;
    }

    /**
     * 重新扫描线程，识别服务端线程并缓存其 ID 与名称。 应在服务启动完成后调用一次。
     *
     * <p>
     * 丢弃已累积的基线：线程集合变了，旧的 {@code lastTotal} 不再可比。
     */
    public void refresh() {
        ThreadInfo[] all = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ThreadInfo info : all) {
            if (info == null || !isServerThread(info.getThreadName())) {
                continue;
            }
            ids.add(info.getThreadId());
            names.add(info.getThreadName());
        }
        ids.sort(Long::compare);
        serverThreadIds = ids.stream().mapToLong(Long::longValue).toArray();
        serverThreadNames = names.toArray(new String[0]);
        lastTotal = 0;
        primed = false;
    }

    /**
     * 取一个统计基线点。此后连续两次 {@link #mark()} 的差值即为该段请求的服务端分配量。
     *
     * <p>
     * 首次调用（未 {@link #refresh()} 或未建立基线）返回 {@link Long#MIN_VALUE}，表示「不可用」——
     * 调用方应丢弃该结果。
     */
    public long mark() {
        long total = sumAllocated();
        if (total < 0) {
            if (!primed) {
                return Long.MIN_VALUE;
            }
            // 已缓存线程全部消失（服务重启/关闭）→ 重扫一次
            refresh();
            total = sumAllocated();
            if (total < 0) {
                return Long.MIN_VALUE;
            }
            // 重扫后总量可比性已被打破（换了线程集合），本次只能作为新基线
            lastTotal = total;
            primed = true;
            return Long.MIN_VALUE;
        }
        long delta = total - lastTotal;
        lastTotal = total;
        primed = true;
        return delta;
    }

    private long sumAllocated() {
        long[] ids = serverThreadIds;
        if (ids.length == 0) {
            return -1;
        }
        long[] values = threadMx.getThreadAllocatedBytes(ids);
        long total = 0;
        for (long v : values) {
            // 负值 = 该线程在读取瞬间已终止（JDK 语义）。跳过它，而非判定整体失败 ——
            // 线程池回收个别 EventLoop 是正常现象，不应让整次测量作废。
            if (v > 0) {
                total += v;
            }
        }
        return total;
    }

    /** 已识别的服务端线程数（0 说明前缀不匹配或服务未启动）。 */
    public int serverThreadCount() {
        return serverThreadIds.length;
    }

    /** 诊断：把「线程数 + 线程名」拼成一行。 */
    public String describe() {
        Set<String> unique = new LinkedHashSet<>(Arrays.asList(serverThreadNames));
        return serverThreadIds.length + " threads " + unique;
    }

    /**
     * 诊断：打印 JVM 全部存活线程的名称与当前分配值，按分配量降序。
     *
     * <p>
     * 保留原因：worker EventLoop 被回收重建、或线程名前缀规则变更导致
     * {@link #mark()} 读到全 0 时，这是唯一能定位「谁在干活」的入口。
     * 2026-10-06 正是靠它发现真正干活的线程是懒创建的 {@code nioEventLoopGroup-3-1}，
     * 而非启动期就存在的 {@code nioEventLoopGroup-2-1}。
     */
    public String dumpAllThreads() {
        long[] ids = threadMx.getAllThreadIds();
        long[] vals = threadMx.getThreadAllocatedBytes(ids);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            if (vals[i] <= 0) {
                continue;
            }
            ThreadInfo info = threadMx.getThreadInfo(ids[i]);
            String name = info == null ? "?" : info.getThreadName();
            boolean server = isServerThread(name);
            lines.add(String.format("%-12d %-44s %s", vals[i], name, server ? "<== SERVER" : ""));
        }
        lines.sort((a, b) -> Long.compare(Long.parseLong(b.trim().split("\\s+")[0]),
                Long.parseLong(a.trim().split("\\s+")[0])));
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append('\n').append("    ").append(l);
        }
        return sb.toString();
    }

    private static boolean isServerThread(String name) {
        for (String prefix : SERVER_THREAD_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
