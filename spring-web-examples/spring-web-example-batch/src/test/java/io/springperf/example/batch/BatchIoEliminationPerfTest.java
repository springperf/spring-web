package io.springperf.example.batch;

import io.springperf.example.batch.perf.PerfIoController;
import io.springperf.example.batch.perf.SimulatedIoRepository;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 量化 batch 聚合对 IO 的消除效果。
 * <p>
 * <b>对照设计</b>：{@code /perf/io/single/{id}} 与 {@code /perf/io/batch/{id}} 调用同一个
 * {@link SimulatedIoRepository}，单条查询与批量查询的模拟延迟严格一致。因此两端的 QPS / IO 次数 / IO 成本差异， 可以完全归因于 「批量消除 IO」这一个变量。
 * </p>
 * <p>
 * <b>负载模型</b>：closed model（固定并发 C + 固定测量窗口 D）。不用固定请求数，因为两种模式的 QPS 可能差 1~2 个数量级—— 固定请求数会让快的轮次只跑几百毫秒（JIT
 * 未热身、噪声大），慢的轮次跑十几秒，两端测量条件不对等。
 * </p>
 * <p>
 * <b>指标取舍</b>：QPS 会受客户端与 HTTP 栈瓶颈影响，且实测噪声大（同配置两次跑可差数倍），因此不作为断言对象，只打印参考。 核心断言落在 「IO 次数」与「每请求 IO 成本」（累计 IO 等待 /
 * 请求数）上——二者只反映 IO 消除本身，与机器性能无关，不会 flake。
 * </p>
 * <p>
 * <b>为什么批大小远低于 {@code maxBatchSize}（实测机制）</b>：{@code BufferingBatchHandler#flush} 用 {@code executor.execute()} 异步提交，IO
 * 在 bizExecutor 而<b>不在</b> Disruptor 消费者线程。消费者线程只在 {@code CallerRunsPolicy} 触发（bizExecutor 满员）时才被阻塞， 于是它几乎总能追上空队列、每拿到 1
 * 个事件就 {@code endOfBatch} 落盘。因此 {@code consumerSize} 与批大小<b>直接对冲</b>， 调大并行度会同时降低攒批能力。本测试用两个 batch 端点把这一端拆开测：
 * </p>
 * <ul>
 * <li>{@code batch-parallel}（{@code consumerSize=50}，吞吐导向）</li>
 * <li>{@code batch-aggregate}（{@code consumerSize=1}，聚合导向）</li>
 * </ul>
 * <p>
 * 实测（本机，JDK 17，100ms IO / 并发 100）：并行档批大小 2.6、每请求 IO 成本 106.65ms→41.08ms（2.6x）； 聚合档批大小 35.0、IO 成本
 * 106.65ms→3.13ms（<b>34x</b>）。结论：<b>「IO 消除」的收益是真实的、量级可达 30~100 倍，但只有在 bizExecutor 饱和、 消费者被迫自己执行 IO
 * 时才兑现。并行度与攒批能力是同一旋钮的两端，当前实现（无时间窗口攒批）下无法同时拿到两者</b>。 若产品目标是两者兼得，需要给 {@code BufferingBatchHandler} 引入「等待 N 毫秒或攒满 M
 * 条」的时间窗口。
 * </p>
 * <p>
 * 运行方式：{@code mvn -pl spring-web-examples/spring-web-example-batch test -Dperf.enabled=true -Dgroups=perf}。 默认（无
 * {@code perf.enabled}）整类禁用，不随常规 {@code mvn test} 跑，对 CI 零影响。
 * </p>
 */
@Tag("perf")
@EnabledIfSystemProperty(named = "perf.enabled", matches = "true")
@SpringBootTest(classes = BatchApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchIoEliminationPerfTest {

    /** 测量窗口：两种模式的采样时长必须一致。 */
    private static final long MEASURE_SECONDS = 4L;

    /** 预热窗口：让 JIT、连接池与 Disruptor/bizExecutor 线程进入稳态，不计入统计。 */
    private static final long WARMUP_SECONDS = 2L;

    private static final int MAX_BATCH_SIZE = 100;

    private int port;

    @Autowired
    private PerfIoController controller;

    @Autowired
    private io.springperf.web.server.NettyHttpServer nettyHttpServer;

    @org.junit.jupiter.api.BeforeEach
    void resolvePort() {
        port = nettyHttpServer.getActualPort();
    }

    static List<Object[]> matrix() {
        // ioLatencyMillis, concurrency —— 延迟扫描「IO 越慢收益越大」；并发是在途请求数的上限，
        // 直接决定 RingBuffer 能否形成积压、进而决定批大小（详见类注释对攒批机制的说明）。
        return Arrays.asList(new Object[] { 0, 100 }, new Object[] { 2, 100 }, new Object[] { 10, 100 },
                new Object[] { 50, 100 }, new Object[] { 100, 100 }, new Object[] { 100, 400 });
    }

    @ParameterizedTest(name = "[{index}] ioLatency={0}ms concurrency={1}")
    @MethodSource("matrix")
    @DisplayName("同源 IO 三路对照：baseline / 吞吐导向 batch / 聚合导向 batch")
    void batchEliminatesIo(int ioLatencyMillis, int concurrency) throws Exception {
        setLatency(controller.repository(), ioLatencyMillis);

        Measurement single = measure(concurrency, "/perf/io/single/");
        Measurement parallel = measure(concurrency, "/perf/io/batch-parallel/");
        Measurement aggregate = measure(concurrency, "/perf/io/batch-aggregate/");
        Measurement window = measure(concurrency, "/perf/io/batch-window/");

        printComparison(ioLatencyMillis, concurrency, single, parallel, aggregate, window);

        // ---- 稳健断言（与机器性能无关，不应 flake） ----

        // 1. baseline 必须是「每请求一次 IO」——否则对照组不成立
        assertThat(single.ioCount).as("baseline 每请求应恰好一次 IO").isEqualTo(single.completed);
        assertThat(single.failures).as("baseline 不应有失败请求").isZero();

        for (Measurement batch : new Measurement[] { parallel, aggregate, window }) {
            String mode = batch == parallel ? "batch-parallel"
                    : batch == aggregate ? "batch-aggregate" : "batch-window";

            // 2. 三个 batch 端点都必须真的在聚合
            assertThat(batch.ioCount).as("%s 的 IO 次数应远小于请求数（发生聚合）", mode).isLessThan(batch.completed);
            assertThat((double) batch.completed / batch.ioCount).as("%s 平均批大小应大于 1", mode).isGreaterThan(1.0);
            assertThat(batch.failures).as("%s 不应有失败请求", mode).isZero();

            // 3. 核心指标：每请求 IO 成本必须显著下降。延迟为 0 时 IO 成本本是 0，跳过。
            // 阈值取 60%：实测各档位降至 2%~20%，留足余量但仍能抓住「IO 消除失效」的回归。
            if (ioLatencyMillis > 0) {
                assertThat(batch.ioCostPerRequestNanos / single.ioCostPerRequestNanos)
                        .as("%s 每请求 IO 成本应降至 baseline 的 60% 以下", mode).isLessThan(0.6);
            }
        }

        // 4. 窗口档必须在不牺牲并行度的前提下拿到大批——这是 consumerSize 与批大小对冲的唯一解，
        // 也是本测试要回答「为什么批大小那么少」的核心断言。
        if (ioLatencyMillis > 0) {
            assertThat((double) window.completed / window.ioCount).as("窗口档的平均批大小应显著大于无窗口的并行档")
                    .isGreaterThan((double) parallel.completed / parallel.ioCount);
        }

        // 注意：不断言 QPS 方向。实测同配置两次跑可差数倍（JIT 热度、调度）。
    }

    @Test
    @DisplayName("零 IO 延迟：验证三条 batch 链路功能正确，且 IO 计数口径成立")
    void zeroLatency_batchPathIsFunctionallyCorrect() throws Exception {
        setLatency(controller.repository(), 0);

        Measurement single = measure(100, "/perf/io/single/");
        Measurement parallel = measure(100, "/perf/io/batch-parallel/");
        Measurement aggregate = measure(100, "/perf/io/batch-aggregate/");
        Measurement window = measure(100, "/perf/io/batch-window/");

        printComparison(0, 100, single, parallel, aggregate, window);

        // 纯内存路径下 IO 成本为 0，比不出高低；这里只验证功能与计数口径：
        // baseline 仍是每请求一次 IO，三条 batch 仍发生了聚合，四端都无失败。
        assertThat(single.ioCount).isEqualTo(single.completed);
        assertThat(parallel.ioCount).isLessThan(parallel.completed);
        assertThat(aggregate.ioCount).isLessThan(aggregate.completed);
        assertThat(window.ioCount).isLessThan(window.completed);
        assertThat(single.failures).isZero();
        assertThat(parallel.failures).isZero();
        assertThat(aggregate.failures).isZero();
        assertThat(window.failures).isZero();
        // QPS 在此场景没有可比性（batch 的框架开销会被放大且噪声大），仅打印不作断言。
    }

    // ------------------------------------------------------------------
    // 负载执行
    // ------------------------------------------------------------------

    private Measurement measure(int concurrency, String path) throws Exception {
        controller.repository().reset();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(concurrency, 5, TimeUnit.MINUTES)).build();

        // 先预热：连接建立 + JIT + Disruptor/bizExecutor 线程就位
        runLoad(client, concurrency, path, WARMUP_SECONDS);
        controller.repository().reset();

        Measurement m = runLoad(client, concurrency, path, MEASURE_SECONDS);
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        return m;
    }

    private Measurement runLoad(OkHttpClient client, int concurrency, String path, long durationSeconds)
            throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(concurrency);
        AtomicInteger failures = new AtomicInteger();
        // 每线程本地收集，避免高 QPS 下共享 list 的竞争
        List<List<Long>> perThreadLatencies = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            perThreadLatencies.add(new ArrayList<>());
        }

        for (int t = 0; t < concurrency; t++) {
            final int idx = t;
            final List<Long> latencies = perThreadLatencies.get(idx);
            workers.submit(() -> {
                Request request = new Request.Builder()
                        .url("http://localhost:" + port + path + "id-" + idx + "-" + System.nanoTime()).build();
                try {
                    startGate.await();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
                    while (System.nanoTime() < deadline) {
                        long t0 = System.nanoTime();
                        try (Response response = client.newCall(request).execute()) {
                            if (response.code() == 200 && response.body() != null) {
                                response.body().string();
                                latencies.add(System.nanoTime() - t0);
                            } else {
                                failures.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(doneGate.await(durationSeconds * 3 + 30, TimeUnit.SECONDS)).as("负载应在超时前结束").isTrue();
        workers.shutdown();

        int total = 0;
        for (List<Long> l : perThreadLatencies) {
            total += l.size();
        }
        long[] merged = new long[total];
        int p = 0;
        for (List<Long> l : perThreadLatencies) {
            for (long v : l) {
                merged[p++] = v;
            }
        }
        Arrays.sort(merged);

        Measurement m = new Measurement();
        m.completed = total;
        m.failures = failures.get();
        m.qps = total == 0 ? 0 : (double) total / durationSeconds;
        m.p50Nanos = percentile(merged, 0.50);
        m.p99Nanos = percentile(merged, 0.99);

        SimulatedIoRepository repo = controller.repository();
        m.ioCount = repo.ioCount();
        m.ioNanos = repo.ioNanos();
        m.ioCostPerRequestNanos = repo.ioCostPerRequestNanos(total);
        return m;
    }

    private static long percentile(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.min(sorted.length - 1, Math.max(0, Math.ceil(q * sorted.length) - 1));
        return sorted[idx];
    }

    private static void setLatency(SimulatedIoRepository repo, int millis) {
        try {
            Field f = SimulatedIoRepository.class.getDeclaredField("ioLatencyNanos");
            f.setAccessible(true);
            f.setLong(repo, millis <= 0 ? 0L : millis * 1_000_000L);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法切换模拟 IO 延迟", e);
        }
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------

    private void printComparison(int ioLatencyMillis, int concurrency, Measurement single, Measurement parallel,
            Measurement aggregate, Measurement window) {
        StringBuilder sb = new StringBuilder(System.lineSeparator());
        sb.append("======== batch IO 消除效果 ======== ioLatency=").append(ioLatencyMillis).append("ms  concurrency=")
                .append(concurrency).append("  maxBatchSize=").append(MAX_BATCH_SIZE).append(System.lineSeparator());
        sb.append(String.format("%-18s %10s %10s %10s %10s %14s %10s%n", "模式", "QPS", "p50(ms)", "p99(ms)", "IO次数",
                "每请求IO(ms)", "批大小"));
        sb.append(row("single", single, 1.0)).append(System.lineSeparator());
        sb.append(row("batch-parallel", parallel, avgBatch(parallel))).append(System.lineSeparator());
        sb.append(row("batch-aggregate", aggregate, avgBatch(aggregate))).append(System.lineSeparator());
        sb.append(row("batch-window", window, avgBatch(window))).append(System.lineSeparator());
        sb.append(String.format("→ IO 成本降幅：parallel %sx · aggregate %sx · window %sx%n",
                ratio(single.ioCostPerRequestNanos, parallel.ioCostPerRequestNanos),
                ratio(single.ioCostPerRequestNanos, aggregate.ioCostPerRequestNanos),
                ratio(single.ioCostPerRequestNanos, window.ioCostPerRequestNanos)));
        sb.append(String.format("→ 批大小：parallel %.1f · aggregate %.1f · window %.1f%n", avgBatch(parallel),
                avgBatch(aggregate), avgBatch(window)));
        sb.append(String.format("→ 请求数 single=%d parallel=%d aggregate=%d window=%d · 失败 %d/%d/%d/%d%n",
                single.completed, parallel.completed, aggregate.completed, window.completed, single.failures,
                parallel.failures, aggregate.failures, window.failures));
        System.out.println(sb);
    }

    private static double avgBatch(Measurement m) {
        return m.ioCount == 0 ? 0 : (double) m.completed / m.ioCount;
    }

    private static String row(String name, Measurement m, double avgBatchSize) {
        return String.format("%-18s %10.0f %10.2f %10.2f %10d %14.4f %10.1f", name, m.qps, m.p50Nanos / 1e6,
                m.p99Nanos / 1e6, m.ioCount, m.ioCostPerRequestNanos / 1e6, avgBatchSize);
    }

    private static String ratio(double numerator, double denominator) {
        if (denominator <= 0) {
            return "n/a";
        }
        double r = numerator / denominator;
        return r >= 100 ? String.format("%.0f", r) : String.format("%.2f", r);
    }

    private static final class Measurement {
        int completed;
        int failures;
        double qps;
        long p50Nanos;
        long p99Nanos;
        long ioCount;
        long ioNanos;
        double ioCostPerRequestNanos;
    }
}
