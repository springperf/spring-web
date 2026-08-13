package io.springperf.benchmark.report.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
        String runMeta = readRunMeta(runDir);
        // run-meta.txt 记录的期望容器列表；非空时用于校验缺失容器（不再静默丢失）
        List<String> expectedProfiles = readExpectedProfiles(runDir);
        String report;
        if (!threadDirs.isEmpty()) {
            report = generateScalabilityReport(runDir, threadDirs, runMeta, expectedProfiles);
        } else {
            // 单线程模式（向后兼容）
            Path jdkDir = findJdkDir(runDir);
            if (jdkDir == null) {
                System.err.println("No JDK subdirectory found in " + runDir);
                System.exit(1);
            }
            report = generateReport(jdkDir, runMeta, expectedProfiles);
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

    /**
     * 读取 run 根目录的 run-meta.txt（wsl-run-all.sh 写入的运行参数），
     * 拼成单行描述（如 mode=thrpt | threads=4 | profiles=... | apis=...）；
     * 无 meta 文件（如历史 run）返回空串，报告不显示该行。
     */
    private static String readRunMeta(Path runDir) {
        Path metaFile = runDir.resolve("run-meta.txt");
        if (!Files.exists(metaFile)) return "";
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : Files.readAllLines(metaFile, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(t);
                }
            }
            return sb.toString();
        } catch (IOException e) {
            System.err.println("[WARN] Failed to read run-meta.txt: " + e.getMessage());
            return "";
        }
    }

    /**
     * 解析 run-meta.txt 的 profiles 字段（wsl-run-all.sh 记录的期望容器列表）。
     * 无 meta 文件、无 profiles 字段或解析失败返回空列表；空列表 = 不校验缺失。
     */
    private static List<String> readExpectedProfiles(Path runDir) {
        Path metaFile = runDir.resolve("run-meta.txt");
        if (!Files.exists(metaFile)) return Collections.emptyList();
        try {
            for (String line : Files.readAllLines(metaFile, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("profiles=")) {
                    List<String> list = new ArrayList<>();
                    for (String p : t.substring("profiles=".length()).split(",")) {
                        String s = p.trim();
                        if (!s.isEmpty()) list.add(s);
                    }
                    return list;
                }
            }
            return Collections.emptyList();
        } catch (IOException e) {
            System.err.println("[WARN] Failed to read run-meta.txt profiles: " + e.getMessage());
            return Collections.emptyList();
        }
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

    static Map<String, Map<String, ProfileData>> discoverAllData(Path jdkDir) throws IOException {
        Map<String, Map<String, ProfileData>> byApi = new LinkedHashMap<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jdkDir, "jmh-results-*.json")) {
            for (Path jmhFile : stream) {
                String fileName = jmhFile.getFileName().toString();
                String stem = fileName.substring("jmh-results-".length());
                stem = stem.substring(0, stem.length() - ".json".length());

                JsonNode root;
                try {
                    root = MAPPER.readTree(jmhFile.toFile());
                } catch (IOException e) {
                    // fork 失败/中断可能残留空文件或截断 JSON：跳过并告警，而不是让整个报告生成崩溃
                    System.err.println("[WARN] Skipping unreadable benchmark result " + jmhFile.getFileName()
                            + ": " + e.getMessage());
                    continue;
                }
                // 空文件：部分 Jackson 版本 readTree 返回 Java null，需一并防御
                if (root == null || !root.isArray()) continue;

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
                    // 单 API：文件名可能为 <profile>-<api>，但 profile 名本身可能含连字符
                    // （如 perf-support）。仅当最后一个 '-' 后是已知 API 名时才按
                    // "profile-api" 拆分，否则把整个 stem 当 profile 名。
                    int lastHyphen = stem.lastIndexOf('-');
                    String tail = stem.substring(lastHyphen + 1);
                    if (KNOWN_APIS.contains(tail)) {
                        profile = stem.substring(0, lastHyphen);
                    } else {
                        profile = stem;
                    }
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

    private static String generateReport(Path jdkDir, String runMeta, List<String> expectedProfiles) throws IOException {
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

        // 期望容器：run-meta 指定时用它做分母，缺失容器计入失败（不再静默丢失）
        List<String> expected = (expectedProfiles == null || expectedProfiles.isEmpty())
                ? Arrays.asList(profiles) : expectedProfiles;
        LinkedHashSet<String> foundSet = new LinkedHashSet<>(Arrays.asList(profiles));
        List<String> missing = new ArrayList<>();
        for (String exp : expected) {
            if (!foundSet.contains(exp)) missing.add(exp);
        }
        int effectiveProfiles = expected.size();
        // 表格列集与摘要分母（effectiveProfiles）保持一致：缺失 profile 也要渲染 FAIL 列，
        // 否则摘要 effectiveFail 计入缺失组合，但表格无对应 FAIL 格，两者自相矛盾。
        // 无期望列表时 expected == profiles，行为不变（向后兼容）。
        String[] tableProfiles = expected.toArray(new String[0]);

        // 成功/失败统计。修复：此前 failCount 只遍历实际发现的 byApi 数据，
        // 缺失的 profile×api 组合（表格渲染为 FAIL 的格子）既不进成功也不进失败，
        // 导致摘要 "33/35 成功，0 失败" 与表格 FAIL 自相矛盾。
        // 现在：success = 发现且含可用数据（吞吐或延迟）；fail = 发现但无可用数据
        //       + 期望组合中无数据的所有格子（表格 FAIL 数 = effectiveFail）。
        int successCount = 0;
        int failCount = 0;
        for (Map<String, ProfileData> profileMap : byApi.values()) {
            for (ProfileData d : profileMap.values()) {
                if (d.success && (!d.throughputs.isEmpty() || !d.percentiles.isEmpty())) {
                    successCount++;
                } else {
                    failCount++;
                }
            }
        }
        List<String> missingCombos = new ArrayList<>();
        for (String exp : expected) {
            for (String api : apis) {
                if (getData(byApi, api, exp) == null) {
                    missingCombos.add(api + " / " + exp);
                }
            }
        }
        int effectiveFail = failCount + missingCombos.size();

        java.io.StringWriter sw = new java.io.StringWriter(4096);
        PrintWriter w = new PrintWriter(sw);

        w.println("# Spring WebPerf 性能对比报告\n");
        w.println("**生成时间:** " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        if (runMeta != null && !runMeta.isEmpty()) {
            w.println();
            w.println("**运行参数:** " + runMeta);
        }
        w.println();
        w.println("**JDK:** " + jdkDir.getFileName().toString());
        w.println();

        w.println("## 执行摘要\n");
        w.printf("发现 **%d** 个 API × **%d** 个容器，总计 **%d/%d** 成功，**%d** 失败",
                totalApis(apis.length, false), effectiveProfiles, successCount,
                apis.length * effectiveProfiles, effectiveFail);
        if (!missing.isEmpty()) {
            w.printf("（期望 %d 个容器，缺失 %d 个）", expected.size(), missing.size());
        }
        w.println();
        w.println();
        if (!missing.isEmpty()) {
            w.println("**⚠️ 缺失容器:** " + String.join(", ", missing)
                    + "（数据未生成，可能是服务端启动失败或压测未执行）");
            w.println();
        }

        writeThroughputSection(w, byApi, tableProfiles, apis, false);
        int sectionNum = 1;
        boolean hasLatency = hasAnyPercentiles(byApi, tableProfiles, apis);
        if (hasLatency) {
            w.printf("## %d. 延迟 (ms, 越低越好)\n\n", ++sectionNum);
            writeLatencySections(w, byApi, tableProfiles, apis);
        }
        w.printf("## %d. GC 行为\n\n", ++sectionNum);
        writeGcSections(w, byApi, tableProfiles, apis, false);
        w.printf("## %d. 内存占用 (稳态)\n\n", ++sectionNum);
        w.println("*内存为容器级稳态快照（同一容器所有 API 共享同一 JVM），非 per-API 数据；external 模式（服务端在远端 JVM）无法采集时显示 N/A。*\n");
        writeMemorySections(w, byApi, tableProfiles, apis);

        if (effectiveFail > 0) {
            w.printf("## %d. 失败项\n\n", ++sectionNum);
            for (Map.Entry<String, Map<String, ProfileData>> se : byApi.entrySet()) {
                String api = se.getKey();
                for (Map.Entry<String, ProfileData> pe : se.getValue().entrySet()) {
                    ProfileData data = pe.getValue();
                    if (!data.success || (data.throughputs.isEmpty() && data.percentiles.isEmpty())) {
                        w.printf("- **%s / %s**: %s\n", api, pe.getKey(),
                                data.failReason != null ? data.failReason : "无可用数据（吞吐/延迟均缺失）");
                    }
                }
            }
            // 缺失组合：表格渲染 FAIL 但 byApi 中无对应数据（JMH 结果未生成）
            for (String combo : missingCombos) {
                w.printf("- **%s**: 数据缺失（JMH 结果未生成，可能是该基准未执行或 fork 失败）\n", combo);
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
    private static String generateScalabilityReport(Path runDir, List<Path> threadDirs, String runMeta,
                                                    List<String> expectedProfiles) throws IOException {
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

        w.println("# Spring WebPerf 性能对比报告（多线程并发测试）\n");
        w.println("**生成时间:** " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        if (runMeta != null && !runMeta.isEmpty()) {
            w.println();
            w.println("**运行参数:** " + runMeta);
        }
        w.println();
        w.println("**线程数:** " + String.join(", ", threadCounts));
        w.println("**JDK:** " + String.join(", ", jdkVersions));
        w.println();

        // 期望容器：run-meta 指定时校验缺失（多线程模式下同样不静默丢失）
        List<String> expected = (expectedProfiles == null || expectedProfiles.isEmpty())
                ? Arrays.asList(profiles) : expectedProfiles;
        LinkedHashSet<String> foundSet = new LinkedHashSet<>(Arrays.asList(profiles));
        List<String> missing = new ArrayList<>();
        for (String exp : expected) {
            if (!foundSet.contains(exp)) missing.add(exp);
        }

        // 表格容器行与期望列表保持一致：缺失容器也渲染 FAIL 行（吞吐/延迟/GC）或 N/A（内存），
        // 与摘要的缺失统计自洽——对齐单线程 generateReport 的 tableProfiles 修法。
        // 修复前表格仅遍历实际发现的 profiles，缺失容器既无表格行也不标 FAIL，与摘要矛盾。
        String[] tableProfiles = expected.toArray(new String[0]);

        w.println("## 执行摘要\n");
        w.printf("**%d** 个容器 × **%d** 个 API × **%d** 个并发度 × **%d** 个 JDK",
                expected.size(), apis.length, threadCounts.size(), jdkVersions.size());
        if (!missing.isEmpty()) {
            w.printf("（期望 %d 个容器，缺失 %d 个：%s）",
                    expected.size(), missing.size(), String.join(", ", missing));
        }
        w.println();
        w.println();
        if (!missing.isEmpty()) {
            w.println("**⚠️ 缺失容器:** " + String.join(", ", missing)
                    + "（数据未生成，可能是服务端启动失败或压测未执行）");
            w.println();
        }

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

            for (String p : tableProfiles) {
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
                for (String p : tableProfiles) {
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
        w.println("*GC 数据优先取 JMH GCProfiler 的 per-API 指标；仅在缺失时回退到容器级 gc.log 聚合解析。*\n");
        for (String api : apis) {
            if ("_default".equals(api)) continue;
            w.printf("### %s\n\n", api);
            w.println("| 容器 | JDK | 线程 | GC 次数 | 平均暂停 | 分配率 | 每请求分配 | Full GC |");
            w.println("|------|-----|------|---------|---------|-------|-----------|---------|");
            for (String p : tableProfiles) {
                for (String jdk : jdkVersions) {
                    for (String tc : threadArr) {
                        ProfileData data = getScalabilityData(allData.get(tc), jdk, api, p);
                        if (data != null && data.success) {
                            String[] cells = gcCellTexts(data);
                            if (cells != null) {
                                w.printf("| %s | %s | %s | %s | %s | %s | %s | %s |\n",
                                        p, jdk, tc, cells[0], cells[1], cells[2], cells[3], cells[4]);
                                continue;
                            }
                        }
                        w.printf("| %s | %s | %s | FAIL | FAIL | FAIL | FAIL | FAIL |\n", p, jdk, tc);
                    }
                }
            }
            w.println();
        }

        // 4. 内存占用
        w.printf("## %d. 内存占用\n\n", ++sectionNum);
        w.println("*内存为容器级稳态快照（同一容器所有 API 共享同一 JVM），非 per-API 数据；external 模式（服务端在远端 JVM）无法采集时显示 N/A。*\n");
        for (String api : apis) {
            if ("_default".equals(api)) continue;
            w.printf("### %s\n\n", api);
            w.println("| 容器 | JDK | 线程 | Heap Used | Metaspace | Code Cache |");
            w.println("|------|-----|------|-----------|-----------|------------|");
            for (String p : tableProfiles) {
                for (String jdk : jdkVersions) {
                    for (String tc : threadArr) {
                        ProfileData data = getScalabilityData(allData.get(tc), jdk, api, p);
                        if (data != null && data.success && data.memorySnapshot != null) {
                            String heapStr = data.memorySnapshot.getHeapUsedMb();
                            String metaStr = extractMemValue(data.memorySnapshot.getNonHeap(), "metaspace");
                            String codeStr = extractMemValue(data.memorySnapshot.getNonHeap(), "code_cache");
                            w.printf("| %s | %s | %s | %s | %s | %s |\n", p, jdk, tc, heapStr, metaStr, codeStr);
                        } else {
                            w.printf("| %s | %s | %s | N/A | N/A | N/A |\n", p, jdk, tc);
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
            w.println("| 容器 | GC 次数 | 平均暂停 | 分配率 | 每请求分配 | Full GC |");
            w.println("|------|---------|---------|-------|-----------|---------|");
            for (String p : profiles) {
                ProfileData data = getData(byApi, api, p);
                if (data != null && data.success) {
                    String[] cells = gcCellTexts(data);
                    if (cells != null) {
                        w.printf("| %s | %s | %s | %s | %s | %s |\n",
                                p, cells[0], cells[1], cells[2], cells[3], cells[4]);
                        continue;
                    }
                }
                w.printf("| %s | FAIL | FAIL | FAIL | FAIL | FAIL |\n", p);
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
                    w.printf("| %s | N/A | N/A | N/A |\n", p);
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
                JsonNode sr = secondaryMetrics.get(key);
                if (sr == null || !sr.has("score")) continue;
                double score = sr.get("score").asDouble();
                if (key.equals("gc.count")) {
                    data.gcProfilerCount = score;
                } else if (key.equals("gc.time")) {
                    data.gcProfilerTimeMs = score;
                } else if (key.equals("gc.alloc.rate")) {
                    // MB/sec，该 benchmark 迭代期间分配率（per-API 准确）
                    data.gcAllocRateMbPerSec = score;
                } else if (key.equals("gc.alloc.rate.norm")) {
                    // B/op，每操作分配字节（per-API 准确）
                    data.gcAllocRateNormBytes = score;
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
        /** JMH GCProfiler per-benchmark 数据（per-API 准确），<0 表示缺失 */
        double gcProfilerCount = -1;
        double gcProfilerTimeMs = -1;
        double gcAllocRateMbPerSec = -1;
        double gcAllocRateNormBytes = -1;
    }

    /**
     * 生成 GC 表格单元格 {GC次数, 平均暂停, 分配率, 每请求分配, FullGC}。
     * <p>
     * 优先使用 JMH GCProfiler 的 per-benchmark 数据（同一容器内各 API 独立、准确）；
     * 缺失时回退到 gc.log 聚合解析（容器级，同一容器所有 API 共享同一份 JVM GC 活动）。
     * 均缺失返回 {@code null}。
     */
    private static String[] gcCellTexts(ProfileData data) {
        if (data.gcProfilerCount >= 0) {
            int gcCount = (int) Math.round(data.gcProfilerCount);
            String avgPause = gcCount > 0
                    ? String.format("%.1fms", data.gcProfilerTimeMs / gcCount) : "0.0ms";
            String rate = data.gcAllocRateMbPerSec > 0.001
                    ? String.format("%.0fMB/s", data.gcAllocRateMbPerSec) : "N/A";
            String perReq = data.gcAllocRateNormBytes > 0
                    ? String.format("%.1fKB", data.gcAllocRateNormBytes / 1024.0) : "N/A";
            return new String[]{String.valueOf(gcCount), avgPause, rate, perReq, "N/A"};
        }
        if (data.gcMetrics != null) {
            GcMetrics gc = data.gcMetrics;
            double throughput = data.throughputs.isEmpty() ? 0 : data.throughputs.values().iterator().next();
            String perReq = "N/A";
            if (gc.getAllocationRateMbPerSec() > 0 && throughput > 0) {
                perReq = String.format("%.1fKB", (gc.getAllocationRateMbPerSec() * 1024) / throughput);
            }
            String rate = gc.getAllocationRateMbPerSec() > 0.001
                    ? String.format("%.0fMB/s", gc.getAllocationRateMbPerSec()) : "N/A";
            return new String[]{String.valueOf(gc.getYoungGcCount()),
                    String.format("%.1fms", gc.getYoungGcAvgMs()), rate, perReq,
                    String.valueOf(gc.getFullGcCount())};
        }
        return null;
    }

    static class PercentileInfo {
        double p50, p90, p99, p999, p9999;
    }
}