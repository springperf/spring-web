package io.springperf.benchmark.report.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDK 11+ 统一日志格式的 GC 日志解析器。
 * <p>
 * 匹配格式示例:
 * {@code [info][gc] GC(0) Pause Young (G1 Evacuation Pause) 50M->10M(200M) 5.123ms}
 * {@code [info][gc] GC(1) Pause Full (G1 Compaction) 180M->30M(200M) 120.340ms}
 */
public class Jdk11GcLogParser implements GcLogParser {

    private static final Pattern YOUNG_PATTERN =
            Pattern.compile("Pause (Young|Mixed)\\s*\\(.*?\\)\\s+.*?\\s+([\\d.]+)ms");
    private static final Pattern FULL_PATTERN =
            Pattern.compile("Pause Full\\s*\\(.*?\\)\\s+.*?\\s+([\\d.]+)ms");
    /** 提取 Young GC 完成行中的 heap 使用量（before/after），用于计算分配率 */
    private static final Pattern HEAP_PATTERN =
            Pattern.compile("Pause Young \\((?:Normal|Concurrent Start)\\).*?(\\d+)M->(\\d+)M\\(\\d+M\\)\\s+[\\d.]+ms");

    @Override
    public boolean supports(Path logFile) {
        try (java.util.stream.Stream<String> lines = Files.lines(logFile)) {
            return lines.limit(5).anyMatch(line -> line.contains("[info][gc]"));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public GcMetrics parse(Path logFile) {
        GcMetrics metrics = new GcMetrics();
        try {
            double youngTotal = 0, youngMax = 0, fullTotal = 0, fullMax = 0;
            int youngCount = 0, fullCount = 0;

            // 分配率计算：追踪相邻 Young GC 之间的 heap 增长
            double prevYoungAfter = -1;
            double totalAllocated = 0;
            double firstGcTime = 0, lastGcTime = 0;

            for (String line : Files.readAllLines(logFile)) {
                // 跳过不含 GC 暂停事件的元数据行（init、metaspace、task 等）
                if (!line.contains("Pause")) {
                    continue;
                }
                // 提取绝对时间戳用于计算持续时长
                double absTime = extractAbsTime(line);

                Matcher youngM = YOUNG_PATTERN.matcher(line);
                if (youngM.find()) {
                    double pause = Double.parseDouble(youngM.group(2));
                    youngTotal += pause;
                    youngMax = Math.max(youngMax, pause);
                    youngCount++;

                    // 分配率：解析 heap before/after，计算相邻 GC 间的分配量
                    Matcher heapM = HEAP_PATTERN.matcher(line);
                    if (heapM.find()) {
                        double before = Double.parseDouble(heapM.group(1));
                        double after = Double.parseDouble(heapM.group(2));
                        if (prevYoungAfter > 0) {
                            totalAllocated += before - prevYoungAfter;
                        }
                        prevYoungAfter = after;
                        if (firstGcTime == 0) firstGcTime = absTime;
                        lastGcTime = absTime;
                    }
                    continue;
                }
                Matcher fullM = FULL_PATTERN.matcher(line);
                if (fullM.find()) {
                    double pause = Double.parseDouble(fullM.group(1));
                    fullTotal += pause;
                    fullMax = Math.max(fullMax, pause);
                    // 跳过 JMH shouldDoGC 触发的 System.gc()，避免 FullGC 计数污染
                    if (!line.contains("System.gc()")) fullCount++;
                }
            }

            // 分配率 = 总分配量 / GC 时间跨度
            double gcDuration = lastGcTime - firstGcTime;
            double allocRate = gcDuration > 0 ? totalAllocated / gcDuration : 0;

            metrics.setYoungGcCount(youngCount)
                    .setYoungGcTotalMs(youngTotal)
                    .setYoungGcMaxMs(youngMax)
                    .setYoungGcAvgMs(youngCount > 0 ? youngTotal / youngCount : 0)
                    .setFullGcCount(fullCount)
                    .setFullGcTotalMs(fullTotal)
                    .setFullGcMaxMs(fullMax)
                    .setTotalAllocatedMb(totalAllocated)
                    .setAllocationRateMbPerSec(allocRate);
        } catch (IOException e) {
            System.err.println("[WARN] Failed to parse GC log: " + logFile + " - " + e.getMessage());
        }
        return metrics;
    }

    /**
     * 从 GC 日志行提取绝对时间（秒）。
     * JDK 11+ unified logging 格式: {@code [2026-07-06T18:59:44.202+0800][2.116s][info][gc...}
     */
    private static double extractAbsTime(String line) {
        // 匹配第二个时间戳字段：[2.116s]
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d+\\.\\d+)s\\]").matcher(line);
        // 第一个是 wall clock 时间，第二个是 JVM uptime
        int count = 0;
        while (m.find()) {
            if (count == 1) {
                return Double.parseDouble(m.group(1));
            }
            count++;
        }
        return 0;
    }
}