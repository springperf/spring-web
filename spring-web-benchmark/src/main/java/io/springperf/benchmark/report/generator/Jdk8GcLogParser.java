package io.springperf.benchmark.report.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDK 8 PrintGCDetails 格式的 GC 日志解析器。
 * <p>
 * 支持分配率计算：从 Young GC 的 Heap: before->after 行提取相邻 GC 间的分配量。
 * <p>
 * 匹配格式示例:
 * {@code 2026-07-07T07:52:28.601+0800: 2.224: [GC pause (G1 Evacuation Pause) (young), 0.0068104 secs]}
 * {@code    [Eden: 10.0M(51.0M)->0.0B(51.0M) Survivors: 0.0B->0.0B Heap: 9730.0K(1024.0M)->2479.9K(1024.0M)]}
 * {@code [Full GC (System.gc())  9729K->2479K(1024M), 0.0110715 secs]}
 */
public class Jdk8GcLogParser implements GcLogParser {

    /** 匹配 Young GC 头行，提取 uptime 和暂停时间 */
    private static final Pattern YOUNG_HEADER =
            Pattern.compile("(\\d+\\.\\d+): \\[GC pause.*?,\\s+(\\d+\\.\\d+)\\s+secs\\]");

    /** 匹配 Full GC 行，提取暂停时间 */
    private static final Pattern FULL_GC =
            Pattern.compile("\\[Full GC\\s.*?(\\d+\\.\\d+)\\s+secs\\]");

    /** 匹配 Young GC 的 Heap: before->after 详情行 */
    private static final Pattern HEAP_DETAIL =
            Pattern.compile("Heap:\\s+([\\d.]+)([KMG]?)\\([\\d.]+[KMG]?\\)->([\\d.]+)([KMG]?)\\([\\d.]+[KMG]?\\)");

    /** 匹配 Full GC 同一行的 before->after */
    private static final Pattern FULL_GC_HEAP =
            Pattern.compile("\\[Full GC.*?(\\d+)([KMG])?->(\\d+)([KMG])?\\(\\d+[KMG]?\\),");

    private static double toMb(double value, String unit) {
        if (unit == null || unit.isEmpty()) return value;
        switch (unit) {
            case "K": return value / 1024.0;
            case "M": return value;
            case "G": return value * 1024.0;
            default: return value;
        }
    }

    @Override
    public boolean supports(Path logFile) {
        try (java.util.stream.Stream<String> lines = Files.lines(logFile)) {
            return lines.limit(50).anyMatch(line ->
                    line.contains("[GC") || line.contains("[Full GC"));
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

            // 分配率计算：追踪相邻 Young GC 间的 heap 增长
            double prevYoungAfter = -1;
            double totalAllocated = 0;
            double firstGcTime = 0, lastGcTime = 0;

            for (String line : Files.readAllLines(logFile)) {
                // === Young GC 头行 ===
                Matcher youngM = YOUNG_HEADER.matcher(line);
                if (youngM.find()) {
                    double pause = Double.parseDouble(youngM.group(2)) * 1000;
                    double uptime = Double.parseDouble(youngM.group(1));
                    youngTotal += pause;
                    youngMax = Math.max(youngMax, pause);
                    youngCount++;
                    if (firstGcTime == 0) firstGcTime = uptime;
                    lastGcTime = uptime;
                    continue;
                }

                // === Young GC heap 详情（可能间隔多行，直接在每行搜索） ===
                Matcher heapM = HEAP_DETAIL.matcher(line);
                if (heapM.find()) {
                    double before = toMb(Double.parseDouble(heapM.group(1)), heapM.group(2));
                    double after = toMb(Double.parseDouble(heapM.group(3)), heapM.group(4));
                    if (prevYoungAfter > 0) {
                        totalAllocated += Math.max(0, before - prevYoungAfter);
                    }
                    prevYoungAfter = after;
                    continue;
                }

                // === Full GC（跳过 JMH shouldDoGC 触发的 System.gc()，避免 FullGC 计数污染） ===
                Matcher fullM = FULL_GC.matcher(line);
                if (fullM.find()) {
                    boolean isSystemGc = line.contains("System.gc()");

                    double pause = Double.parseDouble(fullM.group(1)) * 1000;
                    fullTotal += pause;
                    fullMax = Math.max(fullMax, pause);
                    if (!isSystemGc) fullCount++;

                    // Full GC 后重置 prevYoungAfter，避免分配率计算失真
                    Matcher fHeapM = FULL_GC_HEAP.matcher(line);
                    if (fHeapM.find()) {
                        prevYoungAfter = toMb(Double.parseDouble(fHeapM.group(3)), fHeapM.group(4));
                    }
                }
            }

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
}