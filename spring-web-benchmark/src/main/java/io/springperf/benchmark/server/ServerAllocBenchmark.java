package io.springperf.benchmark.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.springperf.benchmark.app.PerfApplication;
import io.springperf.benchmark.common.BenchClientState;
import io.springperf.benchmark.common.BenchServerState;
import io.springperf.benchmark.common.BenchmarkConstants;
import io.springperf.benchmark.common.ServerThreadAlloc;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 服务端分配量基准 —— 用 TLAB 精确计数替代吞吐采样。
 * <h2>为什么不直接用 JMH 报表输出分配量</h2> 试过两条路，都不可靠：
 * <ul>
 * <li>{@code @AuxCounters(OPERATIONS)}：JMH 会读计数器增量并按 {@code OperationsPerInvocation} 归一。但在
 * {@code @State(Scope.Benchmark)} 宿主类 + {@code @Setup(Level.Iteration)} 组合下， JMH 1.37 输出空结果表（计数器未被识别），且伴随 30s shutdown
 * 超时。</li>
 * <li>把字节数作为 {@code @Benchmark} 返回值：返回值只进 Blackhole，不成为指标。</li>
 * </ul>
 * 根本原因是<strong>分配量不需要统计 machinery</strong>：它是确定性计数，没有采样误差、 不需要置信区间。所以本基准自行做多点测量取中位数，直接写 JSON，不依赖 JMH 报表。
 * <h2>为什么必须与服务端线程绑定</h2> in-process 模式下客户端与服务端同处一个 JVM，{@code -prof gc} 的 {@code gc.alloc.rate.norm}
 * 是<strong>两侧之和</strong>。客户端 OkHttp 自身分配 ~6.8 KB/op， 其波动会淹没服务端差异（实测客户端两版仅差 +0.4%，进程内总和却差 +6.1%）。
 * {@link ServerThreadAlloc} 只读 EventLoop / 业务池线程，把两侧彻底分开。
 * <h2>测量方法</h2> 每个场景做 {@link #SAMPLES} 轮采样，每轮{@link #REQUESTS_PER_SAMPLE} 个请求：
 * <ol>
 * <li>{@code mark()} 取服务端线程分配基线；</li>
 * <li>发一批请求；</li>
 * <li>{@code mark()} 再取一次，差值 ÷ 请求数 = 该轮每请求分配字节。</li>
 * </ol>
 * 取多轮<strong>中位数</strong>输出，消除单轮抖动。结果写入 {@code benchmark.alloc.out}（默认
 * {@code target/benchmark-reports/server-alloc-<profile>.json}）。
 * <h2>线程数</h2> {@code @Threads(1)} + 同步请求 ⇒ 全部请求落在同一个 EventLoop 上，分配统计只覆盖单条路径。 这是有意的：单线程下无跨线程聚合误差，数字最稳。
 * <h2>前置条件</h2> 只支持 in-process 模式（服务端须与客户端同 JVM 才能读线程分配）。external 模式 （{@code benchmark.target}
 * 非空）下服务端在别的进程，{@link #setup()} 直接抛异常， 避免产出误导性的零值。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = { "-Xms1g", "-Xmx1g", "-XX:+UseG1GC" })
@Threads(1)
@State(Scope.Benchmark)
public class ServerAllocBenchmark {

    /** 每轮采样的请求数。取 256 让每轮分配量远超单次读取的计量粒度。 */
    public static final int REQUESTS_PER_SAMPLE = 256;

    /** 采样轮数；取奇数便于取中位数。 */
    public static final int SAMPLES = 5;

    private BenchServerState serverState;
    private BenchClientState clientState;
    private ServerThreadAlloc alloc;

    @Setup(Level.Trial)
    public void setup() {
        if (!BenchmarkConstants.TARGET_HOST.isEmpty()) {
            throw new IllegalStateException("ServerAllocBenchmark requires in-process mode "
                    + "(server must share this JVM to read its thread allocation). Do not set -Dbenchmark.target.");
        }
        Properties props = new Properties();
        props.setProperty("server.port", String.valueOf(BenchmarkConstants.PORT));
        serverState = new BenchServerState(PerfApplication.class, props);
        serverState.start();
        clientState = new BenchClientState();
        clientState.setup(BenchmarkConstants.buildBaseUrl("localhost", serverState.getActualPort()));

        alloc = new ServerThreadAlloc();
        // 此处不 refresh：Netty 的 worker EventLoop 是**懒创建**的 —— 绑定端口时只建 boss，
        // 第一个连接到来才建 worker（实测启动期只有 nioEventLoopGroup-2-1，
        // 真正干活的 nioEventLoopGroup-3-1 在预热后才出现）。
        // refresh() 放在 warmupIteration()，否则只锁到 boss 线程、delta 恒为 0。
    }

    @TearDown(Level.Trial)
    public void teardown() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : results.entrySet()) {
            long[] perSample = e.getValue();
            long[] sorted = perSample.clone();
            java.util.Arrays.sort(sorted);
            result.put(e.getKey() + ".samples", perSample);
            result.put(e.getKey() + ".median", median(sorted));
            result.put(e.getKey() + ".min", sorted[0]);
            result.put(e.getKey() + ".max", sorted[sorted.length - 1]);
        }
        result.put("serverThreads", alloc.describe());
        result.put("requestsPerSample", (long) REQUESTS_PER_SAMPLE);
        result.put("javaVersion", System.getProperty("java.version"));
        writeReport(result);

        if (clientState != null) {
            clientState.cleanup();
        }
        if (serverState != null) {
            serverState.stop();
        }
    }

    private final Map<String, long[]> results = new LinkedHashMap<>();

    /**
     * 每个迭代开始前预热：让 JIT 完成编译、连接池建连、TLAB 进入稳态。
     * <p>
     * 预热不可省 —— 解释执行 / C1 阶段产生的临时对象显著多于 C2 稳态，不预热会系统性高估分配量。
     * <p>
     * <b>顺序至关重要</b>：必须先发请求、再 {@code refresh()}。Netty 的 worker EventLoop 是 懒创建的，只在第一个连接到来时才建；启动期调用 {@code refresh()}
     * 只能锁到 boss 线程， 导致 delta 恒为 0。预热若干请求后worker 已就绪，此时刷新才拿得到真正干活的线程。 刷新后立即 {@code mark()} 建立基线，避免把预热分配算进第一轮采样。
     */
    @Setup(Level.Iteration)
    public void warmupIteration() {
        for (int i = 0; i < 500; i++) {
            try {
                clientState.executeAndConsume(clientState.jsonRequest());
            } catch (Exception e) {
                throw new IllegalStateException("warmup request failed", e);
            }
        }
        alloc.refresh();
        if (alloc.serverThreadCount() == 0) {
            throw new IllegalStateException("No server threads matched after warmup (prefix matching broken).");
        }
        System.out.println("[ServerAlloc] measuring on: " + alloc.describe());
        alloc.mark();
    }

    @Benchmark
    @OperationsPerInvocation(REQUESTS_PER_SAMPLE)
    public void json(Blackhole blackhole) throws Exception {
        results.put("json", sample(blackhole, 0));
    }

    @Benchmark
    @OperationsPerInvocation(REQUESTS_PER_SAMPLE)
    public void get(Blackhole blackhole) throws Exception {
        results.put("get", sample(blackhole, 1));
    }

    @Benchmark
    @OperationsPerInvocation(REQUESTS_PER_SAMPLE)
    public void bytes(Blackhole blackhole) throws Exception {
        results.put("bytes", sample(blackhole, 2));
    }

    /**
     * 跑 {@link #SAMPLES} 轮，每轮 {@link #REQUESTS_PER_SAMPLE} 个请求，返回每请求分配字节的样本数组。
     */
    private long[] sample(Blackhole blackhole, int kind) throws Exception {
        long[] perRequest = new long[SAMPLES];
        for (int s = 0; s < SAMPLES; s++) {
            long before = alloc.mark();
            if (before == Long.MIN_VALUE) {
                throw new IllegalStateException("server thread allocation baseline unavailable");
            }
            for (int i = 0; i < REQUESTS_PER_SAMPLE; i++) {
                switch (kind) {
                    case 0 -> blackhole.consume(clientState.executeAndConsume(clientState.jsonRequest()));
                    case 1 -> blackhole.consume(clientState.executeAndConsume(clientState.getRequest()));
                    default -> blackhole.consume(clientState.executeAndConsume(clientState.bytesRequest()));
                }
            }
            long after = alloc.mark();
            if (after == Long.MIN_VALUE) {
                throw new IllegalStateException("server threads vanished mid-measurement");
            }
            long delta = after - before;
            if (delta == 0) {
                // 唯一的真故障信号：worker EventLoop 在采样间隙被回收重建，
                // 使缓存的线程 ID 失效。刷新并打印全量快照以便确认。
                alloc.refresh();
                System.out.println(
                        "[ServerAlloc] zero delta; refreshing to " + alloc.describe() + alloc.dumpAllThreads());
            }
            perRequest[s] = delta / REQUESTS_PER_SAMPLE;
        }
        return perRequest;
    }

    private static long median(long[] sorted) {
        int n = sorted.length;
        return (n % 2 == 1) ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    /**
     * 写报告。
     * <p>
     * <b>合并而非覆盖</b>：JMH 会为每个 {@code @Benchmark} 方法各跑一次 {@code Level.Trial}（即 {@code @Setup}/{@code @TearDown} 各一次），
     * 若每次都整份覆写，json/get/bytes 中只有<strong>最后一个被测的方法</strong> 会留在报告里。实测该缺陷导致「三端点分配量」永远只看到一项。 故此处对已存在的报告做读-改-写，把新方法的结果并进去。
     */
    private void writeReport(Map<String, Object> result) {
        try {
            String dir = System.getProperty("benchmark.alloc.out.dir", BenchmarkConstants.OUTPUT_DIR);
            Files.createDirectories(Paths.get(dir));
            java.io.File f = new java.io.File(dir, "server-alloc-" + BenchmarkConstants.PROFILE_NAME + ".json");
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> merged = f.exists() ? mapper.readValue(f, Map.class) : new LinkedHashMap<>();
            merged.putAll(result);
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, merged);
            System.out.println("[ServerAlloc] report -> " + f.getAbsolutePath());
            for (Map.Entry<String, Object> e : merged.entrySet()) {
                if (e.getKey().endsWith(".median")) {
                    System.out.println("  " + e.getKey() + " = " + e.getValue() + " B/op");
                }
            }
        } catch (Exception e) {
            System.err.println("[ServerAlloc] failed to write report: " + e);
        }
    }
}
