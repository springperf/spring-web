package io.springperf.benchmark.report.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JMH 多维指标报告生成器。
 * <p>
 * 解析 benchmark-reports/{run-id}/{jdk-version}/ 目录下的所有产物，
 * 合并生成一份 Markdown 报告。
 * <p>
 * 用法: ReportGenerator {@code <benchmark-reports-dir>}
 */
public class ReportGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final GcLogParser[] GC_PARSERS = {
            new Jdk11GcLogParser(),
            new Jdk8GcLogParser()
    };
    private static final String[] PROFILES = {
            "perf", "perf-filter", "perf-support", "perf-support-filter",
            "tomcat", "tomcat-filter",
            "undertow", "undertow-filter",
            "webflux", "webflux-filter"
    };
    private static final String[] BENCHMARKS = {
            "jsonEcho", "helloGet", "asyncDeferredResult",
            "bytes", "validatePost", "jsonEchoLarge", "largeResponse"
    };

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: ReportGenerator <benchmark-reports-dir>");
            System.exit(1);
        }
        Path reportsDir = Paths.get(args[0]);
        if (!Files.isDirectory(reportsDir)) {
            System.err.println("Directory not found: " + reportsDir);
            System.exit(1);
        }

        Path runDir = findLatestRunDir(reportsDir);
        if (runDir == null) {
            System.err.println("No run directory found in " + reportsDir);
            System.exit(1);
        }

        Path jdkDir = findJdkDir(runDir);
        if (jdkDir == null) {
            System.err.println("No JDK subdirectory found in " + runDir);
            System.exit(1);
        }

        String report = generateReport(jdkDir);
        Path reportPath = runDir.resolve("report.md");
        Files.write(reportPath, report.getBytes("UTF-8"));
        System.out.println("Report generated: " + reportPath.toAbsolutePath());

        Path latestDir = reportsDir.resolve("latest");
        Files.createDirectories(latestDir);
        Path latestReport = latestDir.resolve("report.md");
        Files.write(latestReport, report.getBytes("UTF-8"));
        System.out.println("Latest report updated: " + latestReport.toAbsolutePath());
    }

    private static Path findLatestRunDir(Path reportsDir) throws IOException {
        Path latest = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir,
                entry -> Files.isDirectory(entry)
                        && entry.getFileName().toString().matches("\\d{8}-\\d{6}"))) {
            for (Path dir : stream) {
                if (latest == null
                        || dir.getFileName().toString().compareTo(latest.getFileName().toString()) > 0) {
                    latest = dir;
                }
            }
        }
        return latest;
    }

    private static Path findJdkDir(Path runDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(runDir, Files::isDirectory)) {
            for (Path dir : stream) {
                String name = dir.getFileName().toString();
                if (name.startsWith("jdk-") || name.startsWith("1.")) {
                    return dir;
                }
            }
        }
        return runDir;
    }

    private static String generateReport(Path jdkDir) throws IOException {
        java.io.StringWriter sw = new java.io.StringWriter(4096);
        PrintWriter w = new PrintWriter(sw);

        w.println("# Spring Web 性能对比报告\n");
        w.println("**生成时间:** " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        w.println();
        w.println("**JDK:** " + jdkDir.getFileName().toString());
        w.println();

        Map<String, ProfileData> allData = new LinkedHashMap<String, ProfileData>();
        int successCount = 0;
        int failCount = 0;

        for (String profile : PROFILES) {
            ProfileData data = loadProfileData(jdkDir, profile);
            allData.put(profile, data);
            if (data.success) {
                successCount++;
            } else {
                failCount++;
            }
        }

        w.println("## 执行摘要\n");
        w.printf("总计 **%d/%d** profile 完成，**%d** 失败\n\n", successCount, PROFILES.length, failCount);

        // 1. 吞吐量
        w.println("## 1. 吞吐量 (ops/sec, 越高越好)\n");
        printTableHeader(w, PROFILES);
        for (String bench : BENCHMARKS) {
            w.printf("| %s", bench);
            for (String p : PROFILES) {
                ProfileData data = allData.get(p);
                if (data.success && data.throughputs.containsKey(bench)) {
                    w.printf(" | %.0f", data.throughputs.get(bench));
                } else {
                    w.print(" | FAIL");
                }
            }
            w.println(" |");
        }
        w.println();

        // 2. 延迟
        w.println("## 2. 延迟 (ms, 越低越好)\n");
        for (String bench : BENCHMARKS) {
            w.printf("### %s\n\n", bench);
            w.println("| 容器 | p50 | p90 | p99 | p99.9 | p99.99 |");
            w.println("|------|-----|-----|-----|-------|--------|");
            for (String p : PROFILES) {
                ProfileData data = allData.get(p);
                if (data.success && data.percentiles.containsKey(bench)) {
                    PercentileInfo pi = data.percentiles.get(bench);
                    w.printf("| %s | %.2f | %.2f | %.2f | %.2f | %.2f |\n",
                            p, pi.p50, pi.p90, pi.p99, pi.p999, pi.p9999);
                } else {
                    w.printf("| %s | FAIL | FAIL | FAIL | FAIL | FAIL |\n", p);
                }
            }
            w.println();
        }

        // 3. GC
        w.println("## 3. GC 行为\n");
        w.println("| 容器 | Young GC 次数 | 平均暂停 | 最大暂停 | 总暂停时间 | Full GC |");
        w.println("|------|-------------|---------|---------|-----------|---------|");
        for (String p : PROFILES) {
            ProfileData data = allData.get(p);
            if (data.success && data.gcMetrics != null) {
                GcMetrics gc = data.gcMetrics;
                w.printf("| %s | %d | %.1fms | %.1fms | %.1fms | %d |\n",
                        p, gc.getYoungGcCount(), gc.getYoungGcAvgMs(),
                        gc.getYoungGcMaxMs(), gc.getYoungGcTotalMs(), gc.getFullGcCount());
            } else {
                w.printf("| %s | FAIL | FAIL | FAIL | FAIL | FAIL |\n", p);
            }
        }

        // 4. 内存
        w.println("\n## 4. 内存占用 (稳态)\n");
        w.println("| 容器 | Heap Used | Metaspace Used | Code Cache |");
        w.println("|------|-----------|----------------|------------|");
        for (String p : PROFILES) {
            ProfileData data = allData.get(p);
            if (data.success && data.memorySnapshot != null) {
                String heapStr = data.memorySnapshot.getHeapUsedMb();
                String metaStr = extractMemValue(data.memorySnapshot.getNonHeap(), "metaspace");
                String codeStr = extractMemValue(data.memorySnapshot.getNonHeap(), "code_cache");
                w.printf("| %s | %s | %s | %s |\n", p, heapStr, metaStr, codeStr);
            } else {
                w.printf("| %s | FAIL | FAIL | FAIL |\n", p);
            }
        }

        // 5. 失败列表
        if (failCount > 0) {
            w.println("\n## 5. 失败 Profile\n");
            for (String p : PROFILES) {
                ProfileData data = allData.get(p);
                if (!data.success) {
                    w.printf("- **%s**: %s\n", p, data.failReason);
                }
            }
            w.println();
        }

        w.flush();
        return sw.toString();
    }

    private static void printTableHeader(PrintWriter w, String[] profiles) {
        w.print("| 场景");
        for (String p : profiles) {
            w.printf(" | %s", p);
        }
        w.println(" |");
        w.print("|------");
        for (int i = 0; i < profiles.length; i++) {
            w.print("|------");
        }
        w.println("|");
    }

    private static String extractMemValue(Map<String, Object> section, String key) {
        if (section == null) return "N/A";
        Object raw = section.get(key);
        if (raw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) raw;
            Object used = map.get("used");
            if (used instanceof Number) {
                long bytes = ((Number) used).longValue();
                if (bytes > 0) {
                    return String.format("%.0fMB", bytes / 1024.0 / 1024.0);
                }
            }
        }
        return "N/A";
    }

    private static ProfileData loadProfileData(Path jdkDir, String profile) {
        ProfileData data = new ProfileData();
        data.profileName = profile;

        Path jmhFile = jdkDir.resolve("jmh-results-" + profile + ".json");
        if (Files.exists(jmhFile)) {
            try {
                parseJmhResults(jmhFile, data);
                data.success = true;
            } catch (Exception e) {
                data.failReason = "JMH parse error: " + e.getMessage();
            }
        } else {
            data.failReason = "jmh-results-" + profile + ".json not found";
        }

        Path gcFile = jdkDir.resolve("gc-" + profile + ".log");
        if (Files.exists(gcFile)) {
            for (GcLogParser parser : GC_PARSERS) {
                if (parser.supports(gcFile)) {
                    data.gcMetrics = parser.parse(gcFile);
                    break;
                }
            }
        }

        Path memFile = jdkDir.resolve("memory-" + profile + ".json");
        if (Files.exists(memFile)) {
            try {
                data.memorySnapshot = MAPPER.readValue(memFile.toFile(), MemorySnapshot.class);
            } catch (Exception e) {
                System.err.println("[WARN] Failed to parse memory snapshot for " + profile + ": " + e.getMessage());
            }
        }

        return data;
    }

    private static void parseJmhResults(Path jmhFile, ProfileData data) throws IOException {
        JsonNode root = MAPPER.readTree(jmhFile.toFile());
        if (!root.isArray()) return;

        for (JsonNode bench : root) {
            String fullName = bench.has("benchmark") ? bench.get("benchmark").asText() : "";
            String shortName = extractShortName(fullName);
            if (shortName.isEmpty()) continue;
            String mode = bench.has("mode") ? bench.get("mode").asText() : "";

            JsonNode primaryMetric = bench.get("primaryMetric");

            if ("thrpt".equals(mode) && primaryMetric != null && primaryMetric.has("score")) {
                data.throughputs.put(shortName, primaryMetric.get("score").asDouble());
            }

            if ("sample".equals(mode) && primaryMetric != null && primaryMetric.has("scorePercentiles")) {
                JsonNode pcts = primaryMetric.get("scorePercentiles");
                PercentileInfo pi = new PercentileInfo();
                // 百分位值单位是 s/op，转为毫秒
                pi.p50 = getJsonDouble(pcts, "50.0") * 1000;
                pi.p90 = getJsonDouble(pcts, "90.0") * 1000;
                pi.p99 = getJsonDouble(pcts, "99.0") * 1000;
                pi.p999 = getJsonDouble(pcts, "99.9") * 1000;
                pi.p9999 = getJsonDouble(pcts, "99.99") * 1000;
                data.percentiles.put(shortName, pi);
            }
        }
    }

    private static double getJsonDouble(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v != null ? v.asDouble() : 0;
    }

    private static String extractShortName(String fullName) {
        int idx = fullName.lastIndexOf('.');
        return idx >= 0 ? fullName.substring(idx + 1) : fullName;
    }

    static class ProfileData {
        String profileName;
        boolean success;
        String failReason;
        Map<String, Double> throughputs = new LinkedHashMap<String, Double>();
        Map<String, PercentileInfo> percentiles = new LinkedHashMap<String, PercentileInfo>();
        GcMetrics gcMetrics;
        MemorySnapshot memorySnapshot;
    }

    static class PercentileInfo {
        double p50, p90, p99, p999, p9999;
    }
}