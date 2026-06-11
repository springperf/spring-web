package io.springperf.benchmark.report.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDK 8 PrintGCDetails 格式的 GC 日志解析器。
 * <p>
 * 匹配格式示例:
 * {@code [GC (G1 Evacuation Pause) 50M->10M(200M), 5.123 secs]}
 * {@code [Full GC (G1 Compaction) 180M->30M(200M), 120.340 secs]}
 */
public class Jdk8GcLogParser implements GcLogParser {

    private static final Pattern GC_PATTERN =
            Pattern.compile("^\\[GC\\s.*?([\\d.]+)\\s+secs\\]", Pattern.MULTILINE);
    private static final Pattern FULL_GC_PATTERN =
            Pattern.compile("^\\[Full GC\\s.*?([\\d.]+)\\s+secs\\]", Pattern.MULTILINE);

    @Override
    public boolean supports(Path logFile) {
        try (java.util.stream.Stream<String> lines = Files.lines(logFile)) {
            return lines.limit(5).anyMatch(line ->
                    line.startsWith("[GC") || line.startsWith("[Full GC"));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public GcMetrics parse(Path logFile) {
        GcMetrics metrics = new GcMetrics();
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = Files.newBufferedReader(logFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            String content = sb.toString();
            double youngTotal = 0, youngMax = 0;
            int youngCount = 0, fullCount = 0;
            double fullTotal = 0, fullMax = 0;

            Matcher fullMatcher = FULL_GC_PATTERN.matcher(content);
            while (fullMatcher.find()) {
                double pause = Double.parseDouble(fullMatcher.group(1)) * 1000;
                fullTotal += pause;
                fullMax = Math.max(fullMax, pause);
                fullCount++;
            }

            Matcher youngMatcher = GC_PATTERN.matcher(content);
            while (youngMatcher.find()) {
                double pause = Double.parseDouble(youngMatcher.group(1)) * 1000;
                youngTotal += pause;
                youngMax = Math.max(youngMax, pause);
                youngCount++;
            }

            metrics.setYoungGcCount(youngCount)
                    .setYoungGcTotalMs(youngTotal)
                    .setYoungGcMaxMs(youngMax)
                    .setYoungGcAvgMs(youngCount > 0 ? youngTotal / youngCount : 0)
                    .setFullGcCount(fullCount)
                    .setFullGcTotalMs(fullTotal)
                    .setFullGcMaxMs(fullMax);
        } catch (IOException e) {
            System.err.println("[WARN] Failed to parse GC log: " + logFile + " - " + e.getMessage());
        }
        return metrics;
    }
}