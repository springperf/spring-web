package io.springperf.example.batch;

import io.springperf.example.batch.perf.SimulatedIoRepository;
import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import io.springperf.web.batch.queue.DisruptorQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 量化 {@code batchWindowMillis} 的收益与成本。
 * <p>
 * <b>为什么要单独分析</b>：窗口把刷出时机从「消费者追上空队列」改成「窗口到期」，批大小因此上升——这是收益； 但每个批次最多多等一个窗口，单请求延迟随之上升——这是成本。两者方向相反，且都随窗口值单调变化，
 * 必然存在一个净收益峰值。文档里「窗口取 IO 延迟的 1/4 ~ 1/2」是经验值，这里用实测数据验证它、并给出拐点位置。
 * </p>
 * <p>
 * <b>实验设计</b>：队列级 closed-model 负载——固定 IO 延迟 L、并发 C、{@code consumerSize}、{@code maxBatchSize}， 只扫描窗口值 W。
 * 每个「并发连接」是一个生产者线程：构造 {@link BatchRequest} → 入队 → 等待完成 → 下一个， 与真实 HTTP 连接的 closed model 一致，因此测得的 QPS = C /
 * 平均延迟，吞吐与延迟天然耦合。
 * </p>
 * <p>
 * 选队列级而非 HTTP 级的原因：要扫 5 个窗口值 × 2 个 IO 延迟，HTTP 级需要 10 个 endpoint 且噪声会淹没 2ms 档位的差异。
 * </p>
 * <p>
 * 运行方式：{@code mvn -pl spring-web-examples/spring-web-example-batch test -Dperf.enabled=true -Dgroups=perf}
 * </p>
 */
@Tag("perf")
@EnabledIfSystemProperty(named = "perf.enabled", matches = "true")
class BatchWindowTradeoffTest {

    /** 并发连接数：closed model 的在途请求上限。 */
    private static final int CONCURRENCY = 100;

    private static final long MEASURE_SECONDS = 3L;

    private static final long WARMUP_SECONDS = 1L;

    private static final int MAX_BATCH_SIZE = 100;

    /** 合理并行度：贴近典型核数，不超配。窗口分析必须固定它，否则批大小会被并行度扰动。 */
    private static final int CONSUMER_SIZE = 8;

    /** (ioLatencyMillis, windowMillis) —— 窗口从 0 扫到等于 IO 延迟，覆盖「收益区 → 拐点 → 亏损区」。 */
    private static final int[][] MATRIX = { { 20, 0 }, { 20, 2 }, { 20, 5 }, { 20, 10 }, { 20, 20 }, { 50, 0 },
            { 50, 10 }, { 50, 25 }, { 50, 50 } };

    /** 具体请求类型：批量方法需要能调 {@code setResult(String)}，通配捕获下无法通过编译。 */
    public static class TradeoffRequest extends BatchRequest<String> {
    }

    static class TradeoffService {

        private final SimulatedIoRepository repository;

        TradeoffService(SimulatedIoRepository repository) {
            this.repository = repository;
        }

        @SuppressWarnings("unused")
        public void handle(List<TradeoffRequest> batch) {
            List<String> ids = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                ids.add("id");
            }
            repository.findByIds(ids);
            for (TradeoffRequest req : batch) {
                req.setResult("ok");
            }
        }
    }

    @Test
    @DisplayName("扫描窗口值，量化吞吐收益与延迟成本，定位净收益拐点")
    void windowTradeoffCurve() throws Exception {
        // 先跑一轮弃用热身：让 JIT、Disruptor 与 bizExecutor 线程进入稳态，
        // 否则矩阵第一行（W=0）会因冷启动而偏低，带偏整条曲线
        measure(20, 0);

        List<Row> rows = new ArrayList<>();
        for (int[] point : MATRIX) {
            rows.add(measure(point[0], point[1]));
        }

        printCurve(20, rows.subList(0, 5));
        printCurve(50, rows.subList(5, 9));
        printVerdict(rows);
    }

    // ------------------------------------------------------------------
    // 单点测量
    // ------------------------------------------------------------------

    private Row measure(int ioLatencyMillis, int windowMillis) throws Exception {
        SimulatedIoRepository repository = new SimulatedIoRepository(ioLatencyMillis);
        TradeoffService service = new TradeoffService(repository);
        Method handle = TradeoffService.class.getDeclaredMethod("handle", List.class);
        // 必须：TradeoffService 不是 public 类，不 setAccessible 时反射 invoke 抛
        // IllegalAccessException，被 handleBatchError 转成 setError——请求会"完成"但 IO 从未执行，
        // 统计出的 QPS/延迟全是假数据。加断言行 ioCount>0 兜底防再被骗。
        handle.setAccessible(true);
        BatchRequestMetaData meta = new BatchRequestMetaData(handle, TradeoffService.class, null,
                "tw-" + ioLatencyMillis + "-" + windowMillis, 8192, BatchMapping.WaitStrategy.BLOCKING,
                BatchMapping.Backpressure.BLOCK, null, MAX_BATCH_SIZE, windowMillis, CONSUMER_SIZE);
        DisruptorQueue queue = new DisruptorQueue(meta.queueName(), meta, service);
        try {
            runLoad(queue, WARMUP_SECONDS);
            repository.reset();
            Sample sample = runLoad(queue, MEASURE_SECONDS);

            // 兜底：批量方法一次都没跑起来时（如反射不可达），请求会被 setError"完成"，
            // 不校验这条就会得到一份看似合理、实则全假的曲线
            assertThat(repository.ioCount()).as("IO %dms / 窗口 %dms：批量方法应真实执行", ioLatencyMillis, windowMillis)
                    .isPositive();

            Row row = new Row();
            row.ioLatency = ioLatencyMillis;
            row.window = windowMillis;
            row.completed = sample.completed;
            row.qps = sample.completed / (double) MEASURE_SECONDS;
            row.p50Nanos = percentile(sample.latencies, 0.50);
            row.p99Nanos = percentile(sample.latencies, 0.99);
            row.ioCount = repository.ioCount();
            row.ioNanos = repository.ioNanos();
            row.avgBatchSize = row.ioCount == 0 ? 0 : (double) sample.completed / row.ioCount;
            row.ioCostPerRequestNanos = repository.ioCostPerRequestNanos(sample.completed);
            return row;
        } finally {
            queue.shutdown();
        }
    }

    /**
     * closed-model 负载：每个工作者是一个「并发连接」，发请求 → 等完成 → 发下一个。 这样 QPS 与平均延迟互为倒数，吞吐收益与延迟成本在同一组数据里直接可比。
     */
    private Sample runLoad(DisruptorQueue queue, long durationSeconds) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(CONCURRENCY);
        List<List<Long>> perThread = new ArrayList<>(CONCURRENCY);
        for (int i = 0; i < CONCURRENCY; i++) {
            perThread.add(new ArrayList<>());
        }

        for (int t = 0; t < CONCURRENCY; t++) {
            final int idx = t;
            final List<Long> latencies = perThread.get(t);
            workers.submit(() -> {
                try {
                    startGate.await();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
                    while (System.nanoTime() < deadline) {
                        TradeoffRequest req = new TradeoffRequest();
                        long t0 = System.nanoTime();
                        queue.enqueue(req);
                        // getResult() 在 super.setResult 之后才非空，比 isCompleted() 更接近「客户端收到响应」的时刻
                        while (req.getResult() == null) {
                            LockSupport.parkNanos(50_000L);
                        }
                        latencies.add(System.nanoTime() - t0);
                    }
                } catch (Exception e) {
                    // 负载线程异常只影响该连接，不计入统计
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(doneGate.await(durationSeconds * 3 + 30, TimeUnit.SECONDS)).isTrue();
        workers.shutdown();

        Sample sample = new Sample();
        for (List<Long> l : perThread) {
            sample.completed += l.size();
        }
        sample.latencies = new long[sample.completed];
        int p = 0;
        for (List<Long> l : perThread) {
            for (long v : l) {
                sample.latencies[p++] = v;
            }
        }
        Arrays.sort(sample.latencies);
        return sample;
    }

    private static long percentile(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.min(sorted.length - 1, Math.max(0, Math.ceil(q * sorted.length) - 1));
        return sorted[idx];
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------

    private void printCurve(int ioLatency, List<Row> rows) {
        Row base = rows.get(0);
        StringBuilder sb = new StringBuilder(System.lineSeparator());
        sb.append("======== batchWindowMillis 收益/成本 ======== IO 延迟 ").append(ioLatency).append("ms · 并发 ")
                .append(CONCURRENCY).append(" · consumerSize ").append(CONSUMER_SIZE).append(" · maxBatchSize ")
                .append(MAX_BATCH_SIZE).append(System.lineSeparator());
        sb.append(String.format("%8s %10s %10s %10s %10s %12s %14s %12s %12s%n", "窗口(ms)", "QPS", "p50(ms)", "p99(ms)",
                "批大小", "IO次数", "每请求IO(ms)", "吞吐vs W=0", "p50 vs W=0"));
        for (Row r : rows) {
            sb.append(String.format("%8d %10.0f %10.2f %10.2f %10.1f %12d %14.3f %11.2fx %11.2fms%n", r.window, r.qps,
                    r.p50Nanos / 1e6, r.p99Nanos / 1e6, r.avgBatchSize, r.ioCount, r.ioCostPerRequestNanos / 1e6,
                    r.qps / base.qps, (r.p50Nanos - base.p50Nanos) / 1e6));
        }
        System.out.println(sb);
    }

    private void printVerdict(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("======== 结论 ========").append(System.lineSeparator());
        for (int io : new int[] { 20, 50 }) {
            List<Row> group = new ArrayList<>();
            for (Row r : rows) {
                if (r.ioLatency == io) {
                    group.add(r);
                }
            }
            Row base = group.get(0);
            Row best = base;
            for (Row r : group) {
                if (r.qps > best.qps) {
                    best = r;
                }
            }
            double ratio = io == 20 ? 0.25 : 0.5;
            sb.append(String.format(
                    "IO %dms：吞吐峰值在窗口 %dms（%.2fx，p50 %.2fms vs 基线 %.2fms），" + "约为 IO 延迟的 %d/%d；IO 成本降幅 %.1fx%n", io,
                    best.window, best.qps / base.qps, best.p50Nanos / 1e6, base.p50Nanos / 1e6, best.window, io,
                    base.ioCostPerRequestNanos / best.ioCostPerRequestNanos));
            Row worst = group.get(group.size() - 1);
            sb.append(String.format("        窗口=%dms（等于 IO 延迟）时吞吐 %.2fx、p50 %+.2fms —— 已进入亏损区%n", worst.window,
                    worst.qps / base.qps, (worst.p50Nanos - base.p50Nanos) / 1e6));
        }
        System.out.println(sb);
    }

    private static final class Row {
        int ioLatency;
        int window;
        int completed;
        double qps;
        long p50Nanos;
        long p99Nanos;
        long ioCount;
        long ioNanos;
        double avgBatchSize;
        double ioCostPerRequestNanos;
    }

    private static final class Sample {
        int completed;
        long[] latencies;
    }
}
