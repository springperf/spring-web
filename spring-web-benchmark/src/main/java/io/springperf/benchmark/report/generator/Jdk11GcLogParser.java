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
            Pattern.compile("Pause (Young|Mixed)\\(.*?\\)\\s+.*?\\s+([\\d.]+)ms");
    private static final Pattern FULL_PATTERN =
            Pattern.compile("Pause Full\\(.*?\\)\\s+.*?\\s+([\\d.]+)ms");

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

            for (String line : Files.readAllLines(logFile)) {
                Matcher youngM = YOUNG_PATTERN.matcher(line);
                if (youngM.find()) {
                    double pause = Double.parseDouble(youngM.group(2));
                    youngTotal += pause;
                    youngMax = Math.max(youngMax, pause);
                    youngCount++;
                    continue;
                }
                Matcher fullM = FULL_PATTERN.matcher(line);
                if (fullM.find()) {
                    double pause = Double.parseDouble(fullM.group(1));
                    fullTotal += pause;
                    fullMax = Math.max(fullMax, pause);
                    fullCount++;
                }
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