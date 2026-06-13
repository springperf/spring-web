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
import java.util.*;

/**
 * JMH 多维指标报告生成器（Per-Scenario 版）。
 * <p>
 * 从 benchmark-reports/{run-id}/{jdk-version}/ 目录动态发现所有
 * {@code jmh-results-{profile}-{scenario}.json} 文件，按 scenario 维度
 * 组织数据并生成 Markdown 报告。不依赖硬编码 profile/scenario 列表。
 * <p>
 * 文件命名解析规则：去掉前缀 "jmh-results-" 和后缀 ".json"，
 * 按最后一个 "-" 分割，左侧为 profile 名，右侧为 scenario 名。
 * 例如 {@code jmh-results-perf-filter-jsonEcho.json} →
 * profile="perf-filter", scenario="jsonEcho"。
 * <p>
 * 用法: ReportGenerator {@code <benchmark-reports-dir>}
 */
public class ReportGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final GcLogParser[] GC_PARSERS = {
            new Jdk11GcLogParser(),
            new Jdk8GcLogParser()
    };

    /** 已知场景顺序参考，用于报告排序 */
    private static final List<String> KNOWN_SCENARIOS = Arrays.asList(
            "jsonEcho", "helloGet", "asyncDeferredResult",
            "bytes", "validatePost", "jsonEchoLarge", "largeResponse",
            "sseStream"
    );

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

    // ==================== 新：动态文件发现 ====================

    /**
     * 扫描 JDK 目录下所有 jmh-results-*.json 文件，从文件名提取 profile+scenario。
     * <p>
     * 返回 {@code scenario -> profile -> ProfileData} 的两层 Map。
     */
    private static Map<String, Map<String, ProfileData>> discoverAllData(Path jdkDir) throws IOException {
        // scenario -> profile -> ProfileData
        Map<String, Map<String, ProfileData>> byScenario = new LinkedHashMap<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jdkDir, "jmh-results-*.json")) {
            for (Path jmhFile : stream) {
                String fileName = jmhFile.getFileName().toString();
                // "jmh-results-perf-filter-jsonEcho.json"
                String stem = fileName.substring("jmh-results-".length());
                stem = stem.substring(0, stem.length() - ".json".length());
                // stem = "perf-filter-jsonEcho"

                String profile, scenario;
                int lastHyphen = stem.lastIndexOf('-');
                if (lastHyphen > 0) {
                    profile = stem.substring(0, lastHyphen);
                    scenario = stem.substring(lastHyphen + 1);
                } else {
                    // 旧格式 jmh-results-{profile}.json，无 scenario
                    profile = stem;
                    scenario = "_default";
                }

                ProfileData data = new ProfileData();
                data.profileName = profile;
                data.scenario = scenario;
                try {
                    parseJmhResults(jmhFile, data);
                    data.success = true;
                } catch (Exception e) {
                    data.failReason = "JMH parse error: " + e.getMessage();
                    System.err.println("[WARN] Failed to parse " + fileName + ": " + e.getMessage());
                }

                // 加载 GC 日志 gc-{profile}-{scenario}.log
                Path gcFile = jdkDir.resolve("gc-" + profile + "-" + scenario + ".log");
                if (Files.exists(gcFile)) {
                    for (GcLogParser parser : GC_PARSERS) {
                        if (parser.supports(gcFile)) {
                            data.gcMetrics = parser.parse(gcFile);
                            break;
                        }
                    }
                }

                // 加载内存快照 memory-{profile}-{scenario}.json
                Path memFile = jdkDir.resolve("memory-" + profile + "-" + scenario + ".json");
                if (Files.exists(memFile)) {
                    try {
                        data.memorySnapshot = MAPPER.readValue(memFile.toFile(), MemorySnapshot.class);
                    } catch (Exception e) {
                        System.err.println("[WARN] Failed to parse memory snapshot for "
                                + fileName + ": " + e.getMessage());
                    }
                }

                byScenario.computeIfAbsent(scenario, k -> new LinkedHashMap<>())
                        .put(profile, data);
            }
        }

        return byScenario;
    }

    private static String generateReport(Path jdkDir) throws IOException {
        Map<String, Map<String, ProfileData>> byScenario = discoverAllData(jdkDir);

        // 构建有序的 profiles 和 scenarios 列表
        LinkedHashSet<String> allProfiles = new LinkedHashSet<>();
        LinkedHashSet<String> allScenarios = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, ProfileData>> entry : byScenario.entrySet()) {
            String scenario = entry.getKey();
            // 按 KNOWN_SCENARIOS 顺序插入；未知 scenario 按发现顺序
            if (KNOWN_SCENARIOS.contains(scenario)) {
                allScenarios.add(scenario);
            }
            allProfiles.addAll(entry.getValue().keySet());
        }
        // 确保已知 scenario 按定义顺序
        for (String known : KNOWN_SCENARIOS) {
            if (byScenario.containsKey(known)) {
                allScenarios.add(known);
            }
        }
        // 未知 scenario 追加到末尾
        for (String key : byScenario.keySet()) {
            if (!KNOWN_SCENARIOS.contains(key)) {
                allScenarios.add(key);
            }
        }

        String[] profiles = allProfiles.toArray(new String[0]);
        String[] scenarios = allScenarios.toArray(new String[0]);

        int totalScenarios = scenarios.length;
        int totalProfiles = profiles.length;
        int successCount = 0;
        int failCount = 0;
        for (Map<String, ProfileData> profileMap : byScenario.values()) {
            for (ProfileData d : profileMap.values()) {
                if (d.success) successCount++;
                else failCount++;
            }
        }

        java.io.StringWriter sw = new java.io.StringWriter(4096);
        PrintWriter w = new PrintWriter(sw);

        w.println("# Spring Web 性能对比报告\n");
        w.println("**生成时间:** " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        w.println();
        w.println("**JDK:** " + jdkDir.getFileName().toString());
        w.println();

        w.println("## 执行摘要\n");
        w.printf("发现 **%d** 个场景 × **%d** 个容器，总计 **%d/%d** 成功，**%d** 失败\n\n",
                totalScenarios, totalProfiles, successCount,
                totalScenarios * totalProfiles, failCount);

        // 1. 吞吐量
        w.println("## 1. 吞吐量 (ops/sec, 越高越好)\n");
        printTableHeader(w, profiles);
        for (String sc : scenarios) {
            w.printf("| %s", sc);
            for (String p : profiles) {
                ProfileData data = getData(byScenario, sc, p);
                if (data != null && data.success && !data.throughputs.isEmpty()) {
                    // 取第一个（也是唯一一个）throughput 值
                    double val = data.throughputs.values().iterator().next();
                    w.printf(" | %.0f", val);
                } else {
                    w.print(" | FAIL");
                }
            }
            w.println(" |");
        }
        w.println();

        // 2. 延迟
        w.println("## 2. 延迟 (ms, 越低越好)\n");
        for (String sc : scenarios) {
            if ("_default".equals(sc)) continue; // 旧格式无 scenario，跳过延迟表
            w.printf("### %s\n\n", sc);
            w.println("| 容器 | p50 | p90 | p99 | p99.9 | p99.99 |");
            w.println("|------|-----|-----|-----|-------|--------|");
            for (String p : profiles) {
                ProfileData data = getData(byScenario, sc, p);
                if (data != null && data.success && !data.percentiles.isEmpty()) {
                    PercentileInfo pi = data.percentiles.values().iterator().next();
                    w.printf("| %s | %.2f | %.2f | %.2f | %.2f | %.2f |\n",
                            p, pi.p50, pi.p90, pi.p99, pi.p999, pi.p9999);
                } else {
                    w.printf("| %s | FAIL | FAIL | FAIL | FAIL | FAIL |\n", p);
                }
            }
            w.println();
        }

        // 3. GC 行为（按 scenario）
        w.println("## 3. GC 行为\n");
        for (String sc : scenarios) {
            if ("_default".equals(sc)) continue;
            w.printf("### %s\n\n", sc);
            w.println("| 容器 | Young GC 次数 | 平均暂停 | 最大暂停 | 总暂停时间 | Full GC |");
            w.println("|------|-------------|---------|---------|-----------|---------|");
            for (String p : profiles) {
                ProfileData data = getData(byScenario, sc, p);
                if (data != null && data.success && data.gcMetrics != null) {
                    GcMetrics gc = data.gcMetrics;
                    w.printf("| %s | %d | %.1fms | %.1fms | %.1fms | %d |\n",
                            p, gc.getYoungGcCount(), gc.getYoungGcAvgMs(),
                            gc.getYoungGcMaxMs(), gc.getYoungGcTotalMs(), gc.getFullGcCount());
                } else if (data != null && data.success && data.gcProfilerCount >= 0) {
                    w.printf("| %s | %.0f | N/A(GCProfiler) | N/A | %.0fms | N/A |\n",
                            p, data.gcProfilerCount, data.gcProfilerTimeMs);
                } else {
                    w.printf("| %s | FAIL | FAIL | FAIL | FAIL | FAIL |\n", p);
                }
            }
            w.println();
        }

        // 4. 内存占用（按 scenario）
        w.println("## 4. 内存占用 (稳态)\n");
        for (String sc : scenarios) {
            if ("_default".equals(sc)) continue;
            w.printf("### %s\n\n", sc);
            w.println("| 容器 | Heap Used | Metaspace Used | Code Cache |");
            w.println("|------|-----------|----------------|------------|");
            for (String p : profiles) {
                ProfileData data = getData(byScenario, sc, p);
                if (data != null && data.success && data.memorySnapshot != null) {
                    String heapStr = data.memorySnapshot.getHeapUsedMb();
                    String metaStr = extractMemValue(data.memorySnapshot.getNonHeap(), "metaspace");
                    String codeStr = extractMemValue(data.memorySnapshot.getNonHeap(), "code_cache");
                    w.printf("| %s | %s | %s | %s |\n", p, heapStr, metaStr, codeStr);
                } else {
                    w.printf("| %s | FAIL | FAIL | FAIL |\n", p);
                }
            }
            w.println();
        }

        // 5. 失败列表
        if (failCount > 0) {
            w.println("## 5. 失败项\n");
            for (Map.Entry<String, Map<String, ProfileData>> se : byScenario.entrySet()) {
                String sc = se.getKey();
                for (Map.Entry<String, ProfileData> pe : se.getValue().entrySet()) {
                    ProfileData data = pe.getValue();
                    if (!data.success) {
                        w.printf("- **%s / %s**: %s\n", sc, pe.getKey(), data.failReason);
                    }
                }
            }
            w.println();
        }

        w.flush();
        return sw.toString();
    }

    private static ProfileData getData(Map<String, Map<String, ProfileData>> byScenario,
                                        String scenario, String profile) {
        Map<String, ProfileData> profileMap = byScenario.get(scenario);
        return profileMap != null ? profileMap.get(profile) : null;
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

            // 从 GCProfiler secondaryMetrics 提取 GC 指标（所有 mode 都包含，只取一次）
            JsonNode secondaryMetrics = bench.get("secondaryMetrics");
            if (secondaryMetrics != null && data.gcProfilerCount < 0) {
                for (java.util.Iterator<String> it = secondaryMetrics.fieldNames(); it.hasNext(); ) {
                    String key = it.next();
                    if (key.equals("gc.count")) {
                        JsonNode sr = secondaryMetrics.get(key);
                        if (sr.has("score")) data.gcProfilerCount = sr.get("score").asDouble();
                    } else if (key.equals("gc.time")) {
                        JsonNode sr = secondaryMetrics.get(key);
                        if (sr.has("score")) data.gcProfilerTimeMs = sr.get("score").asDouble();
                    }
                }
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
        String scenario;
        boolean success;
        String failReason;
        Map<String, Double> throughputs = new LinkedHashMap<String, Double>();
        Map<String, PercentileInfo> percentiles = new LinkedHashMap<String, PercentileInfo>();
        GcMetrics gcMetrics;
        MemorySnapshot memorySnapshot;
        /** GCProfiler 兜底数据（当外部 GC 日志不可用时使用） */
        double gcProfilerCount = -1;
        double gcProfilerTimeMs = -1;
    }

    static class PercentileInfo {
        double p50, p90, p99, p999, p9999;
    }
}