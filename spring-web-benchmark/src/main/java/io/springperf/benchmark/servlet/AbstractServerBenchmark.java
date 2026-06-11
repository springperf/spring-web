package io.springperf.benchmark.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.springperf.benchmark.common.BenchClientState;
import io.springperf.benchmark.common.BenchServerState;
import io.springperf.benchmark.common.BenchmarkConstants;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 基准测试基类 — 所有 Server Benchmark 共享 setup/teardown/benchmark 方法。
 * <p>
 * 子类只需覆盖 {@link #getApplicationClass()} 返回对应的 Spring Boot 启动类。
 * JMH 注解集中管理，保证 5 个 profile 使用完全一致的 Benchmark 配置。
 * 注意：不能用 abstract 关键字，否则 JMH 注解处理器生成的 _jmhType 子类无法编译。
 * <p>
 * ⚠ JMH Fork 说明：{@link BenchmarkConstants#FORKS}=1 确保每次 benchmark method 运行时
 * 有独立的 JVM 进程，避免 JIT 编译 / GC 行为交叉污染。jvmArgs 统一为 1G G1GC 堆。
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = BenchmarkConstants.WARMUP_ITERATIONS,
        time = BenchmarkConstants.WARMUP_TIME_SECONDS, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = BenchmarkConstants.MEASUREMENT_ITERATIONS,
        time = BenchmarkConstants.MEASUREMENT_TIME_SECONDS, timeUnit = TimeUnit.SECONDS)
@Fork(value = BenchmarkConstants.FORKS, jvmArgs = {
        "-Xms1g", "-Xmx1g",
        "-XX:+UseG1GC",
        "-XX:+AlwaysPreTouch"
})
@Threads(BenchmarkConstants.THREADS)
@State(Scope.Benchmark)
public class AbstractServerBenchmark {

    /** 由子类覆盖返回启动类。直接使用会抛异常。 */
    protected Class<?> getApplicationClass() {
        throw new UnsupportedOperationException(
                "Subclass must override getApplicationClass()");
    }

    private BenchServerState serverState;
    private BenchClientState clientState;

    @Setup(Level.Trial)
    public void setup() {
        Class<?> appClass = getApplicationClass();
        Properties props = new Properties();
        props.setProperty("server.port", String.valueOf(BenchmarkConstants.PORT));
        serverState = new BenchServerState(appClass, props);
        serverState.start();
        clientState = new BenchClientState();
        clientState.setup();
    }

    @TearDown(Level.Trial)
    public void teardown() {
        collectMemorySnapshot();
        clientState.cleanup();
        serverState.stop();
    }

    // ==================== Memory Snapshot ====================

    private void collectMemorySnapshot() {
        try {
            System.gc();
            Thread.sleep(100);

            MemoryMXBean mxBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = mxBean.getHeapMemoryUsage();
            MemoryUsage nonHeap = mxBean.getNonHeapMemoryUsage();

            Map<String, Object> young = new LinkedHashMap<>();
            Map<String, Object> old = new LinkedHashMap<>();
            Map<String, Object> metaspace = new LinkedHashMap<>();
            Map<String, Object> codeCache = new LinkedHashMap<>();

            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                String name = pool.getName();
                MemoryUsage usage = pool.getUsage();
                if (usage == null) continue;
                Map<String, Object> target;
                if (name.contains("Young") || name.contains("Eden") || name.contains("Survivor") || name.contains("S0") || name.contains("S1")) {
                    target = young;
                } else if (name.contains("Old") || name.contains("Tenured")) {
                    target = old;
                } else if (name.contains("Metaspace")) {
                    target = metaspace;
                } else if (name.contains("Code") || name.contains("Profiling")) {
                    target = codeCache;
                } else {
                    continue;
                }
                target.put("name", name);
                target.put("used", usage.getUsed());
                target.put("max", usage.getMax() == -1 ? -1 : usage.getMax());
                target.put("committed", usage.getCommitted());
            }

            Runtime runtime = Runtime.getRuntime();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("profile", BenchmarkConstants.PROFILE_NAME);
            data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            data.put("jdk_version", System.getProperty("java.version"));
            data.put("jdk_vendor", System.getProperty("java.vendor"));

            Map<String, Object> heapMap = new LinkedHashMap<>();
            heapMap.put("used", heap.getUsed());
            heapMap.put("committed", heap.getCommitted());
            heapMap.put("max", heap.getMax() == -1 ? -1 : heap.getMax());
            heapMap.put("young", young);
            heapMap.put("old", old);
            data.put("heap", heapMap);

            Map<String, Object> nonHeapMap = new LinkedHashMap<>();
            nonHeapMap.put("used", nonHeap.getUsed());
            nonHeapMap.put("committed", nonHeap.getCommitted());
            nonHeapMap.put("metaspace", metaspace);
            nonHeapMap.put("code_cache", codeCache);
            data.put("non_heap", nonHeapMap);
            data.put("available_processors", runtime.availableProcessors());

            String outputDir = BenchmarkConstants.OUTPUT_DIR;
            Files.createDirectories(Paths.get(outputDir));
            String path = outputDir + "/memory-" + BenchmarkConstants.PROFILE_NAME + ".json";

            new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(Paths.get(path).toFile(), data);
        } catch (Exception e) {
            System.err.println("[WARN] Failed to collect memory snapshot: " + e.getMessage());
        }
    }

    // ==================== Benchmark Methods ====================

    @Benchmark
    public void jsonEcho(Blackhole blackhole) throws Exception {
        blackhole.consume(clientState.executeAndConsume(clientState.jsonEchoRequest));
    }

    @Benchmark
    public void helloGet(Blackhole blackhole) throws Exception {
        blackhole.consume(clientState.executeAndConsume(clientState.helloGetRequest));
    }

    @Benchmark
    public void asyncDeferredResult(Blackhole blackhole) throws Exception {
        blackhole.consume(clientState.executeAndConsume(clientState.asyncGetRequest));
    }

    @Benchmark
    public void bytes(Blackhole blackhole) throws Exception {
        blackhole.consume(clientState.executeAndConsume(clientState.bytesGetRequest));
    }

    @Benchmark
    public void validatePost(Blackhole blackhole) throws Exception {
        blackhole.consume(clientState.executeAndConsume(clientState.validatePostRequest));
    }

    // ==================== P0 新场景 ====================

    @Benchmark
    public void jsonEchoLarge(Blackhole blackhole) throws Exception {
        blackhole.consume(clientState.executeAndConsume(clientState.jsonEchoLargeRequest));
    }

    @Benchmark
    public void largeResponse(Blackhole blackhole) throws Exception {
        blackhole.consume(clientState.executeAndConsume(clientState.largeResponseGetRequest));
    }
}