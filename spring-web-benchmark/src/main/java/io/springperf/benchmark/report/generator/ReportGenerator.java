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
 * JMH 多维指标报告生成器（Per-API 版）。
 * <p>
 * 从 benchmark-reports/{run-id}/{jdk-version}/ 目录动态发现所有
 * {@code jmh-results-{profile}-{api}.json} 文件，按 api 维度
 * 组织数据并生成 Markdown 报告。不依赖硬编码 profile/api 列表。
 * <p>
 * 支持多线程并发测试：检测 runDir 下 threads-N 子目录结构，
 * 自动生成并发伸缩性对比矩阵。
 */
public class ReportGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final GcLogParser[] GC_PARSERS = {
            new Jdk11GcLogParser(),
            new Jdk8GcLogParser()
    };

    private static final List<String> KNOWN_APIS = Arrays.asList(
            "json", "get", "async",
            "bytes", "valid", "bytesLarge",
            "sse"
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

        // 检测多线程并发测试结构
        List<Path> threadDirs = findThreadDirs(runDir);
        String report;
        if (!threadDirs.isEmpty()) {
            report = generateScalabilityReport(runDir, threadDirs);
        } else {
            // 单线程模式（向后兼容）
            Path jdkDir = findJdkDir(runDir);
            if (jdkDir == null) {
                System.err.println("No JDK subdirectory found in " + runDir);
                System.exit(1);
            }
            report = generateReport(jdkDir);
        }

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

    // ==================== 多线程并发测试检测 ====================

    /**
     * 检测 runDir 下是否有 threads-N 子目录结构。
     * 返回按目录名排序的列表（如 threads-1, threads-4, threads-16, threads-64）。
     */
    private static List<Path> findThreadDirs(Path runDir) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(runDir,
                entry -> Files.isDirectory(entry) && entry.getFileName().toString().matches("threads-\\d+"))) {
            for (Path dir : stream) {
                dirs.add(dir);
            }
        }
        Collections.sort(dirs); // lexicographic sort works for thread counts (1, 16, 4, 64 → 1, 16, 4, 64 numerically sorted later)
        return dirs;
    }

    // ==================== 数据发现 ====================

    private static Map<String, Map<String, ProfileData>> discoverAllData(Path jdkDir) throws IOException {
        Map<String, Map<String, ProfileData>> byApi = new LinkedHashMap<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jdkDir, "jmh-results-*.json")) {
            for (Path jmhFile : stream) {
                String fileName = jmhFile.getFileName().toString();
                String stem = fileName.substring("jmh-results-".length());
                stem = stem.substring(0, stem.length() - ".json".length());

                JsonNode root = MAPPER.readTree(jmhFile.toFile());
                if (!root.isArray()) continue;

                Map<String, ProfileData> perApiData = new LinkedHashMap<>();
                Set<String> apisInFile = new LinkedHashSet<>();

                for (JsonNode bench : root) {
                    String fullName = bench.has("benchmark") ? bench.get("benchmark").asText() : "";
                    String shortName = extractShortName(fullName);
                    if (shortName.isEmpty()) continue;
                    apisInFile.add(shortName);

                    ProfileData data = perApiData.get(shortName);
                    if (data == null) {
                        data = new ProfileData();
                        data.api = shortName;
                        perApiData.put(shortName, data);
                    }
                    parseBenchmarkEntry(bench, data);
                }

                if (perApiData.isEmpty()) continue;

                String profile;
                if (apisInFile.size() > 1 || !stem.contains("-")) {
                    profile = stem;
                } else {
                    int lastHyphen = stem.lastIndexOf('-');
                    profile = stem.substring(0, lastHyphen);
                }

                Path gcFileShared = jdkDir.resolve("gc-" + profile + ".log");
                GcMetrics sharedGcMetrics = null;
                if (Files.exists(gcFileShared)) {
                    for (GcLogParser parser : GC_PARSERS) {
                        if (parser.supports(gcFileShared)) {
                            sharedGcMetrics = parser.parse(gcFileShared);
                            break;
                        }
                    }
                }

                Path memFileShared = jdkDir.resolve("memory-" + profile + ".json");
                MemorySnapshot sharedMemorySnapshot = null;
                if (Files.exists(memFileShared)) {
                    try {
                        sharedMemorySnapshot = MAPPER.readValue(memFileShared.toFile(), MemorySnapshot.class);
                    } catch (Exception e) {
                        System.err.println("[WARN] Failed to parse memory snapshot for "
                                + fileName + ": " + e.getMessage());
                    }
                }

                for (Map.Entry<String, ProfileData> entry : perApiData.entrySet()) {
                    ProfileData data = entry.getValue();
                    data.profileName = profile;
                    data.success = true;

                    if (sharedGcMetrics != null) {
                        data.gcMetrics = sharedGcMetrics;
                    } else {
                        Path gcFile = jdkDir.resolve("gc-" + profile + "-" + data.api + ".log");
                        if (Files.exists(gcFile)) {
                            for (GcLogParser parser : GC_PARSERS) {
                                if (parser.supports(gcFile)) {
                                    data.gcMetrics = parser.parse(gcFile);
                                    break;
                                }
                            }
                        }
                    }

                    if (sharedMemorySnapshot != null) {
                        data.memorySnapshot = sharedMemorySnapshot;
                    } else {
                        Path memFile = jdkDir.resolve("memory-" + profile + "-" + data.api + ".json");
                        if (Files.exists(memFile)) {
                            try {
                                data.memorySnapshot = MAPPER.readValue(memFile.toFile(), MemorySnapshot.class);
                            } catch (Exception e) {
                                System.err.println("[WARN] Failed to parse memory snapshot for "
                                        + fileName + ": " + e.getMessage());
                            }
                        }
                    }

                    byApi.computeIfAbsent(data.api, k -> new LinkedHashMap<>())
                            .put(profile, data);
                }
            }
        }

        return byApi;
    }

    // ==================== 单线程报告（向后兼容） ====================

    private static String generateReport(Path jdkDir) throws IOException {
        Map<String, Map<String, ProfileData>> byApi = discoverAllData(jdkDir);

        LinkedHashSet<String> allProfiles = new LinkedHashSet<>();
        LinkedHashSet<String> allApis = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, ProfileData>> entry : byApi.entrySet()) {
            String api = entry.getKey();
            if (KNOWN_APIS.contains(api)) {
                allApis.add(api);
            }
            allProfiles.addAll(entry.getValue().keySet());
        }
        for (String known : KNOWN_APIS) {
            if (byApi.containsKey(known)) {
                allApis.add(known);
            }
        }
        for (String key : byApi.keySet()) {
            if (!KNOWN_APIS.contains(key)) {
                allApis.add(key);
            }
        }

        String[] profiles = allProfiles.toArray(new String[0]);
        String[] apis = allApis.toArray(new String[0]);

        int successCount = 0;
        int failCount = 0;
        for (Map<String, ProfileData> profileMap : byApi.values()) {
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
        w.printf("发现 **%d** 个 API × **%d** 个容器，总计 **%d/%d** 成功，**%d** 失败\n\n",
                totalApis(apis.length, false), profiles.length, successCount,
                apis.length * profiles.length, failCount);

        writeThroughputSection(w, byApi, profiles, apis, false);
        int sectionNum = 1;
        boolean hasLatency = hasAnyPercentiles(byApi, profiles, apis);
        if (hasLatency) {
            w.printf("## %d. 延迟 (ms, 越低越好)\n\n", ++sectionNum);
            writeLatencySections(w, byApi, profiles, apis);
        }
        w.printf("## %d. GC 行为\n\n", ++sectionNum);
        writeGcSections(w, byApi, profiles, apis, false);
        w.printf("## %d. 内存占用 (稳态)\n\n", ++sectionNum);
        writeMemorySections(w, byApi, profiles, apis);

        if (failCount > 0) {
            w.println("## 5. 失败项\n");
            for (Map.Entry<String, Map<String, ProfileData>> se : byApi.entrySet()) {
                String api = se.getKey();
                for (Map.Entry<String, ProfileData> pe : se.getValue().entrySet()) {
                    ProfileData data = pe.getValue();
                    if (!data.success) {
                        w.printf("- **%s / %s**: %s\n", api, pe.getKey(), data.failReason);
                    }
                }
            }
            w.println();
        }

        w.flush();
        return sw.toString();
    }

    // ==================== 多线程伸缩性报告 ====================

    /**
     * 生成多线程并发伸缩性报告。
     * 检测 runDir 下所有 threads-N 子目录，收集各并发度的数据，
     * 生成吞吐量随线程数变化的矩阵，并以中间线程数做详细对比。
     */
    private static String generateScalabilityReport(Path runDir, List<Path> threadDirs) throws IOException {
        // threadCount -> jdkVersion -> api -> profile -> ProfileData
        LinkedHashMap<String, LinkedHashMap<String, Map<String, Map<String, ProfileData>>>> allData = new LinkedHashMap<>();
        List<String> threadCounts = new ArrayList<>();
        LinkedHashSet<String> allJdkVersions = new LinkedHashSet<>();

        for (Path threadDir : threadDirs) {
            String dirName = threadDir.getFileName().toString();
            String threadCount = dirName.substring("threads-".length());
            threadCounts.add(threadCount);

            LinkedHashMap<String, Map<String, Map<String, ProfileData>>> jdkData = new LinkedHashMap<>();
            List<Path> jdkDirs = findAllJdkDirs(threadDir);
            for (Path jdkDir : jdkDirs) {
                String jdkName = jdkDir.getFileName().toString()
                        .replace("jdk-", "").replace("_", ".");
                allJdkVersions.add(jdkName);

                Map<String, Map<String, ProfileData>> byApi = discoverAllData(jdkDir);
                if (!byApi.isEmpty()) {
                    jdkData.put(jdkName, byApi);
                }
            }

            if (!jdkData.isEmpty()) {
                allData.put(threadCount, jdkData);
            }
        }

        if (allData.isEmpty()) {
            return "# 无可用数据\n\n未在任何 threads-N 子目录中发现基准结果。\n";
        }

        // 数字排序
        threadCounts.sort(Comparator.comparingInt(Integer::parseInt));
        List<String> jdkVersions = new ArrayList<>(allJdkVersions);

        // 收集所有 API 和 profile
        LinkedHashSet<String> allProfiles = new LinkedHashSet<>();
        LinkedHashSet<String> allApis = new LinkedHashSet<>();
        for (LinkedHashMap<String, Map<String, Map<String, ProfileData>>> jdkMap : allData.values()) {
            for (Map<String, Map<String, ProfileData>> byApi : jdkMap.values()) {
                for (String api : byApi.keySet()) {
                    if (KNOWN_APIS.contains(api)) allApis.add(api);
                    for (Map<String, ProfileData> profileMap : byApi.values()) {
                        allProfiles.addAll(profileMap.keySet());
                    }
                }
            }
        }

        String[] profiles = allProfiles.toArray(new String[0]);
        String[] apis = allApis.toArray(new String[0]);

        java.io.StringWriter sw = new java.io.StringWriter(8192);
        PrintWriter w = new PrintWriter(sw);

        w.println("# Spring Web 性能对比报告（多线程并发测试）\n");
        w.println("**生成时间:** " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        w.println();
        w.println("**线程数:** " + String.join(", ", threadCounts));
        w.println("**JDK:** " + String.join(", ", jdkVersions));
        w.println();

        w.println("## 执行摘要\n");
        w.printf("**%d** 个容器 × **%d** 个 API × **%d** 个并发度 × **%d** 个 JDK\n\n",
                profiles.length, apis.length, threadCounts.size(), jdkVersions.size());

        // ==================== 1. 并发伸缩性 ====================
        w.println("## 1. 并发伸缩性 (ops/sec, 越高越好)\n");
        for (String api : apis) {
            w.printf("### %s\n\n", api);
            w.print("| 容器 | JDK");
            for (String tc : threadCounts) {
                w.printf(" | %s线程", tc);
            }
            w.println(" |");
            w.print("|------|-----");
            for (String tc : threadCounts) {
                w.print("|--------");
            }
            w.println("|");

            for (String p : profiles) {
                for (String jdk : jdkVersions) {
                    w.printf("| %s | %s", p, jdk);
                    for (String tc : threadCounts) {
                        ProfileData data = getScalabilityData(allData.get(tc), jdk, api, p);
                        if (data != null && data.success && !data.throughputs.isEmpty()) {
                            w.printf(" | %.0f", data.throughputs.values().iterator().next());
                        } else {
                            w.print(" | FAIL");
                        }
                    }
                    w.println(" |");
                }
            }
            w.println();
        }

        // ==================== 2-4: 详细分析 ====================
        String[] threadArr = threadCounts.toArray(new String[0]);
        int sectionNum = 1;

        // 2. 延迟分析（仅在有延迟数据时输出）
        boolean hasLatency = hasAnyPercentiles(allData, threadCounts, profiles, apis, jdkVersions);
        if (hasLatency) {
            w.printf("## %d. 延迟分析 (ms)\n\n", ++sectionNum);
            for (String api : apis) {
                if ("_default".equals(api)) continue;
                w.printf("### %s\n\n", api);
                w.println("| 容器 | JDK | 线程 | p50 | p90 | p99 | p99.9 | p99.99 |");
                w.println("|------|-----|------|-----|-----|-----|-------|--------|");
                for (String p : profiles) {
                    for (String jdk : jdkVersions) {
                        for (String tc : threadArr) {
                            ProfileData data = getScalabilityData(allData.get(tc), jdk, api, p);
                            if (data != null && data.success && !data.percentiles.isEmpty()) {
                                PercentileInfo pi = data.percentiles.values().iterator().next();
                                w.printf("| %s | %s | %s | %.2f | %.2f | %.2f | %.2f | %.2f |\n",
                                        p, jdk, tc, pi.p50, pi.p90, pi.p99, pi.p999, pi.p9999);
                            } else {
                                w.printf("| %s | %s | %s | FAIL | FAIL | FAIL | FAIL | FAIL |\n", p, jdk, tc);
                            }
                        }
                    }
                }
                w.println();
            }
        }

        // 3. GC 行为
        w.printf("## %d. GC行为\n\n", ++sectionNum);
        for (String api : apis) {
            if ("_default".equals(api)) continue;
            w.printf("### %s\n\n", api);
            w.println("| 容器 | JDK | 线程 | Young GC | 平均暂停 | 分配率 | 每请求分配 | Full GC |");
            w.println("|------|-----|------|----------|---------|-------|-----------|---------|");
            for (String p : profiles) {
                for (String jdk : jdkVersions) {
                    for (String tc : threadArr) {
                        ProfileData data = getScalabilityData(allData.get(tc), jdk, api, p);
                        if (data != null && data.success && data.gcMetrics != null) {
                            GcMetrics gc = data.gcMetrics;
                            double tp = data.throughputs.isEmpty() ? 0 : data.throughputs.values().iterator().next();
                            String perReq = "N/A";
                            if (gc.getAllocationRateMbPerSec() > 0 && tp > 0) {
                                perReq = String.format("%.1fKB", (gc.getAllocationRateMbPerSec() * 1024) / tp);
                            }
                            String rate = gc.getAllocationRateMbPerSec() > 0.001
                                    ? String.format("%.0fMB/s", gc.getAllocationRateMbPerSec()) : "N/A";
                            w.printf("| %s | %s | %s | %d | %.1fms | %s | %s | %d |\n",
                                    p, jdk, tc, gc.getYoungGcCount(), gc.getYoungGcAvgMs(),
                                    rate, perReq, gc.getFullGcCount());
                        } else {
                            w.printf("| %s | %s | %s | FAIL | FAIL | FAIL | FAIL | FAIL |\n", p, jdk, tc);
                        }
                    }
                }
            }
            w.println();
        }

        // 4. 内存占用
        w.printf("## %d. 内存占用\n\n", ++sectionNum);
        for (String api : apis) {
            if ("_default".equals(api)) continue;
            w.printf("### %s\n\n", api);
            w.println("| 容器 | JDK | 线程 | Heap Used | Metaspace | Code Cache |");
            w.println("|------|-----|------|-----------|-----------|------------|");
            for (String p : profiles) {
                for (String jdk : jdkVersions) {
                    for (String tc : threadArr) {
                        ProfileData data = getScalabilityData(allData.get(tc), jdk, api, p);
                        if (data != null && data.success && data.memorySnapshot != null) {
                            String heapStr = data.memorySnapshot.getHeapUsedMb();
                            String metaStr = extractMemValue(data.memorySnapshot.getNonHeap(), "metaspace");
                            String codeStr = extractMemValue(data.memorySnapshot.getNonHeap(), "code_cache");
                            w.printf("| %s | %s | %s | %s | %s | %s |\n", p, jdk, tc, heapStr, metaStr, codeStr);
                        } else {
                            w.printf("| %s | %s | %s | FAIL | FAIL | FAIL |\n", p, jdk, tc);
                        }
                    }
                }
            }
            w.println();
        }

        w.flush();
        return sw.toString();
    }

    /** 查找 threadDir 下所有 JDK 子目录 */
    private static List<Path> findAllJdkDirs(Path threadDir) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(threadDir,
                entry -> Files.isDirectory(entry) && entry.getFileName().toString().matches("jdk-.*|1\\..*"))) {
            for (Path dir : stream) {
                dirs.add(dir);
            }
        }
        Collections.sort(dirs);
        return dirs;
    }

    /** 从 3 级 Map 中按 jdk → api → profile 链路获取数据 */
    private static ProfileData getScalabilityData(
            LinkedHashMap<String, Map<String, Map<String, ProfileData>>> jdkMap,
            String jdk, String api, String profile) {
        if (jdkMap == null) return null;
        Map<String, Map<String, ProfileData>> byApi = jdkMap.get(jdk);
        if (byApi == null) return null;
        Map<String, ProfileData> profileMap = byApi.get(api);
        return profileMap != null ? profileMap.get(profile) : null;
    }

    
    // ==================== 报告节选共享方法 ====================

    private static int totalApis(int count, boolean multiThread) {
        return count;
    }

    private static void writeThroughputSection(PrintWriter w,
            Map<String, Map<String, ProfileData>> byApi,
            String[] profiles, String[] apis, boolean multiThread) {
        w.println("## 1. 吞吐量 (ops/sec, 越高越好)\n");
        printTableHeader(w, profiles, multiThread);
        for (String api : apis) {
            w.printf("| %s", api);
            for (String p : profiles) {
                ProfileData data = getData(byApi, api, p);
                if (data != null && data.success && !data.throughputs.isEmpty()) {
                    double val = data.throughputs.values().iterator().next();
                    w.printf(" | %.0f", val);
                } else {
                    w.print(" | FAIL");
                }
            }
            w.println(" |");
        }
        w.println();
    }

    private static void writeLatencySections(PrintWriter w,
            Map<String, Map<String, ProfileData>> byApi,
            String[] profiles, String[] apis) {
        for (String api : apis) {
            if ("_default".equals(api)) continue;
            w.printf("### %s\n\n", api);
            w.println("| 容器 | p50 | p90 | p99 | p99.9 | p99.99 |");
            w.println("|------|-----|-----|-----|-------|--------|");
            for (String p : profiles) {
                ProfileData data = getData(byApi, api, p);
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
    }

    private static void writeGcSections(PrintWriter w,
            Map<String, Map<String, ProfileData>> byApi,
            String[] profiles, String[] apis, boolean multiThread) {
        for (String api : apis) {
            if ("_default".equals(api)) continue;
            w.printf("### %s\n\n", api);
            w.println("| 容器 | Young GC | 平均暂停 | 分配率 | 每请求分配 | Full GC |");
            w.println("|------|----------|---------|-------|-----------|---------|");
            for (String p : profiles) {
                ProfileData data = getData(byApi, api, p);
                if (data != null && data.success && data.gcMetrics != null) {
                    GcMetrics gc = data.gcMetrics;
                    double throughput = 0;
                    if (!data.throughputs.isEmpty()) {
                        throughput = data.throughputs.values().iterator().next();
                    }
                    String perReq = "N/A";
                    if (gc.getAllocationRateMbPerSec() > 0 && throughput > 0) {
                        double kbPerReq = (gc.getAllocationRateMbPerSec() * 1024) / throughput;
                        perReq = String.format("%.1fKB", kbPerReq);
                    }
                    String allocRate;
                    double rawRate = gc.getAllocationRateMbPerSec();
                    if (rawRate > 0.001) {
                        allocRate = String.format("%.0fMB/s", rawRate);
                    } else {
                        allocRate = "N/A";
                    }
                    w.printf("| %s | %d | %.1fms | %s | %s | %d |\n",
                            p, gc.getYoungGcCount(), gc.getYoungGcAvgMs(),
                            allocRate, perReq, gc.getFullGcCount());
                } else if (data != null && data.success && data.gcProfilerCount >= 0) {
                    w.printf("| %s | %.0f | N/A(GCProfiler) | N/A | N/A | N/A |\n",
                            p, data.gcProfilerCount);
                } else {
                    w.printf("| %s | FAIL | FAIL | FAIL | FAIL | FAIL |\n", p);
                }
            }
            w.println();
        }
    }

    private static void writeMemorySections(PrintWriter w,
            Map<String, Map<String, ProfileData>> byApi,
            String[] profiles, String[] apis) {
        for (String api : apis) {
            if ("_default".equals(api)) continue;
            w.printf("### %s\n\n", api);
            w.println("| 容器 | Heap Used | Metaspace Used | Code Cache |");
            w.println("|------|-----------|----------------|------------|");
            for (String p : profiles) {
                ProfileData data = getData(byApi, api, p);
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
    }

    /** 检查是否有任何数据包含百分位信息（用于判断是否显示延迟章节） */
    private static boolean hasAnyPercentiles(
            Map<String, Map<String, ProfileData>> byApi,
            String[] profiles, String[] apis) {
        for (String api : apis) {
            if ("_default".equals(api)) continue;
            for (String p : profiles) {
                ProfileData data = getData(byApi, api, p);
                if (data != null && data.success && !data.percentiles.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 多线程版：遍历所有 threadCount × jdk × api × profile */
    private static boolean hasAnyPercentiles(
            Map<String, LinkedHashMap<String, Map<String, Map<String, ProfileData>>>> allData,
            List<String> threadCounts, String[] profiles, String[] apis, List<String> jdkVersions) {
        for (String tc : threadCounts) {
            LinkedHashMap<String, Map<String, Map<String, ProfileData>>> jdkMap = allData.get(tc);
            if (jdkMap == null) continue;
            for (String jdk : jdkVersions) {
                for (String api : apis) {
                    if ("_default".equals(api)) continue;
                    for (String p : profiles) {
                        ProfileData data = getScalabilityData(jdkMap, jdk, api, p);
                        if (data != null && data.success && !data.percentiles.isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static ProfileData getData(Map<String, Map<String, ProfileData>> byApi,
                                        String api, String profile) {
        if (byApi == null) return null;
        Map<String, ProfileData> profileMap = byApi.get(api);
        return profileMap != null ? profileMap.get(profile) : null;
    }

    private static void printTableHeader(PrintWriter w, String[] profiles, boolean multiThread) {
        w.print("| API");
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

    private static void parseBenchmarkEntry(JsonNode bench, ProfileData data) {
        String fullName = bench.has("benchmark") ? bench.get("benchmark").asText() : "";
        String shortName = extractShortName(fullName);
        if (shortName.isEmpty()) return;
        String mode = bench.has("mode") ? bench.get("mode").asText() : "";

        JsonNode primaryMetric = bench.get("primaryMetric");

        if ("thrpt".equals(mode) && primaryMetric != null && primaryMetric.has("score")) {
            data.throughputs.put(shortName, primaryMetric.get("score").asDouble());
        }

        if ("sample".equals(mode) && primaryMetric != null && primaryMetric.has("scorePercentiles")) {
            JsonNode pcts = primaryMetric.get("scorePercentiles");
            PercentileInfo pi = new PercentileInfo();
            pi.p50 = getJsonDouble(pcts, "50.0") * 1000;
            pi.p90 = getJsonDouble(pcts, "90.0") * 1000;
            pi.p99 = getJsonDouble(pcts, "99.0") * 1000;
            pi.p999 = getJsonDouble(pcts, "99.9") * 1000;
            pi.p9999 = getJsonDouble(pcts, "99.99") * 1000;
            data.percentiles.put(shortName, pi);
        }

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

    private static double getJsonDouble(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v != null ? v.asDouble() : 0;
    }

    private static String extractShortName(String fullName) {
        int idx = fullName.lastIndexOf('.');
        return idx >= 0 ? fullName.substring(idx + 1) : fullName;
    }

    // ==================== 内联数据类型 ====================

    static class ProfileData {
        String profileName;
        String api;
        boolean success;
        String failReason;
        Map<String, Double> throughputs = new LinkedHashMap<String, Double>();
        Map<String, PercentileInfo> percentiles = new LinkedHashMap<String, PercentileInfo>();
        GcMetrics gcMetrics;
        MemorySnapshot memorySnapshot;
        double gcProfilerCount = -1;
        double gcProfilerTimeMs = -1;
    }

    static class PercentileInfo {
        double p50, p90, p99, p999, p9999;
    }
}