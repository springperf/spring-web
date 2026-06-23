package io.springperf.benchmark;

import io.springperf.benchmark.common.BenchmarkConstants;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.StackProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.ArrayList;
import java.util.List;

/**
 * JMH 程序化运行器（开发调试用）。
 * <p>
 * <b>⚠ 重要：本运行器使用 {@code forks=0}（不 fork JVM），
 * 因此所有 benchmark method 共享 JVM，JIT/GC 存在交叉污染。
 * 仅用于开发调试，正式结果请使用 {@code mvn -P benchmark-xxx jmh:benchmark}。</b>
 * <p>
 * 配合 {@link BenchmarkConstants} 确保 warmup / measurement / threads 等设置与注解一致。
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws Exception {
        // 兼容 exec:java（工作目录是父项目）和直接 java -jar 运行
        String baseDir = System.getProperty("benchmark.output.dir",
                new java.io.File("spring-web-benchmark/target").isDirectory()
                        ? "spring-web-benchmark/target" : "target");
        // 根据 profile name 构建结果文件名（如 jmh-results-tomcat.json）
        String profileName = System.getProperty("benchmark.profile.name", "unknown");
        String resultFile = baseDir + "/jmh-results-" + profileName + ".json";

        // 支持覆盖端口（避免多 profile 串行时的端口冲突）
        String portOverride = System.getProperty("benchmark.port");
        if (portOverride != null) {
            System.setProperty("benchmark.port", portOverride);
            System.out.println("[BenchmarkRunner] Port override: " + portOverride);
        } else {
            System.out.println("[BenchmarkRunner] Port: " + BenchmarkConstants.PORT);
        }
        System.out.println("[BenchmarkRunner] Result file: " + new java.io.File(resultFile).getAbsolutePath());

        // 支持按类名过滤（避免 classpath 污染导致跨 profile 混淆）
        String includePattern = System.getProperty("benchmark.include", ".*");
        System.out.println("[BenchmarkRunner] Include pattern: " + includePattern);

        // 确保结果文件目录存在
        new java.io.File(resultFile).getParentFile().mkdirs();

        // 读取 fork 和 JVM 参数（由脚本传入），默认 fork=0 保持向后兼容
        int forks = Integer.getInteger("jmh.forks", 0);
        String gcLogArg = System.getProperty("benchmark.gc.log.arg", "");
        System.out.println("[BenchmarkRunner] Forks: " + forks);

        OptionsBuilder optBuilder = new OptionsBuilder();
        optBuilder.include(includePattern);
        optBuilder.forks(forks);
        optBuilder.warmupIterations(BenchmarkConstants.WARMUP_ITERATIONS);
        optBuilder.warmupTime(TimeValue.seconds(BenchmarkConstants.WARMUP_TIME_SECONDS));
        optBuilder.measurementIterations(BenchmarkConstants.MEASUREMENT_ITERATIONS);
        optBuilder.measurementTime(TimeValue.seconds(BenchmarkConstants.MEASUREMENT_TIME_SECONDS));
        int threads = Integer.getInteger("benchmark.threads", BenchmarkConstants.THREADS);
	        optBuilder.threads(threads);
        optBuilder.mode(Mode.Throughput);
        optBuilder.mode(Mode.SampleTime);
        optBuilder.addProfiler(GCProfiler.class);
        // StackProfiler：benchmark.stack=true 时启用 ThreadMXBean CPU 采样
        String stackEnabled = System.getProperty("benchmark.stack");
        String stackParams = System.getProperty("benchmark.stack.params", "");
        if ("true".equalsIgnoreCase(stackEnabled)) {
            if (!stackParams.isEmpty()) {
                optBuilder.addProfiler(StackProfiler.class, stackParams);
                System.out.println("[BenchmarkRunner] StackProfiler enabled with params: " + stackParams);
            } else {
                optBuilder.addProfiler(StackProfiler.class);
                System.out.println("[BenchmarkRunner] StackProfiler enabled");
            }
        }
        optBuilder.resultFormat(ResultFormatType.JSON);
        optBuilder.result(resultFile);
        optBuilder.shouldDoGC(true);

        // 收集传递给 forked JVM 的额外 JVM 参数
        List<String> extraJvmArgs = new ArrayList<>();

        // GC 日志参数
        if (!gcLogArg.isEmpty()) {
            String[] parts = gcLogArg.split("\\s+");
            java.util.Collections.addAll(extraJvmArgs, parts);
            System.out.println("[BenchmarkRunner] JVM args append: " + java.util.Arrays.toString(parts));
        }

        // JFR 录音参数：benchmark.jfr=true 时对 forked JVM 启用飞行记录
        String jfrEnabled = System.getProperty("benchmark.jfr");
        if ("true".equalsIgnoreCase(jfrEnabled)) {
            String jfrFile = baseDir + "/jfr-" + profileName.replace('/', '-') + ".jfr";
            // JDK 8 需要额外解锁商业特性，JDK 11+ 直接可用
            String javaVersion = System.getProperty("java.version", "");
            if (javaVersion.startsWith("1.")) {
                extraJvmArgs.add("-XX:+UnlockCommercialFeatures");
                extraJvmArgs.add("-XX:+FlightRecorder");
            }
            extraJvmArgs.add("-XX:StartFlightRecording=duration=300s,filename=" + jfrFile
                    + ",settings=profile,maxsize=256m");
            System.out.println("[BenchmarkRunner] JFR recording enabled: " + jfrFile);
        }

        // 传递 benchmark.* 系统属性到 forked JVM（内存快照、端口、profile名等需要）
        String[] propNames = {"benchmark.port", "benchmark.profile.name", "benchmark.output.dir"};
        for (String propName : propNames) {
            String propValue = System.getProperty(propName);
            if (propValue != null && !propValue.isEmpty()) {
                extraJvmArgs.add("-D" + propName + "=" + propValue);
            }
        }

        // SSE 调试期间启用 DEBUG 日志
        extraJvmArgs.add("-Dlogging.level.io.springperf=DEBUG");

        if (!extraJvmArgs.isEmpty()) {
            optBuilder.jvmArgsAppend(extraJvmArgs.toArray(new String[0]));
        }

        Options opt = optBuilder.build();

        new Runner(opt).run();

        // 注意：不在此处调用 System.exit()，由外层脚本控制 profile 切换。
        // 各 profile 通过不同的 benchmark.port 避免端口冲突。
    }
}
