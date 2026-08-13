package io.springperf.benchmark.report.generator;

import io.springperf.benchmark.report.generator.ReportGenerator.ProfileData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：{@code discoverAllData} 遇到空文件 / 截断 JSON（fork 失败残留）时，
 * 修复前 {@code MAPPER.readTree} 抛 {@code IOException} 一路冒泡到 {@code main}，
 * 导致整个报告生成崩溃；修复后应跳过并告警，正常文件照常解析。
 */
class ReportGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void discoverAllData_skipsEmptyOrTruncatedJson_andKeepsValidFile() throws Exception {
        // 有效文件：perf profile 一条 thrpt 结果
        String valid = "[{\"benchmark\":\"io.springperf.benchmark.servlet.PerfBenchmark.json\","
                + "\"mode\":\"thrpt\",\"primaryMetric\":{\"score\":1234.0}}]";
        Files.write(tempDir.resolve("jmh-results-perf.json"), valid.getBytes(StandardCharsets.UTF_8));
        // 空文件（fork 失败残留 0 字节）
        Files.write(tempDir.resolve("jmh-results-tomcat.json"), new byte[0]);
        // 截断 JSON（写入一半即中断）
        Files.write(tempDir.resolve("jmh-results-undertow.json"),
                "[{\"benchmark\":\"io.springperf.benchmark.servlet.UndertowBenchmark\""
                        .getBytes(StandardCharsets.UTF_8));

        Map<String, Map<String, ProfileData>> byApi = ReportGenerator.discoverAllData(tempDir);

        assertFalse(byApi.isEmpty(), "有效文件应被解析，坏文件应被跳过而非崩溃");
        assertTrue(byApi.containsKey("json"), "valid file 的 API 应存在");
        Map<String, ProfileData> profileMap = byApi.get("json");
        assertTrue(profileMap.containsKey("perf"), "valid file 的 profile 应存在");
        assertEquals(1234.0, profileMap.get("perf").throughputs.get("json"), 0.001);
    }

    /**
     * 回归 P2（diff 专项）：期望容器中整容器缺失时，主表格必须渲染该 profile 的 FAIL 列，
     * 与摘要分母（effectiveProfiles）一致。修复前表格只用实际发现的 profiles 作列，
     * 缺失 profile 无列 → 表格 FAIL 格数 ≠ 摘要 effectiveFail，两者自相矛盾。
     */
    @Test
    void generateReport_tableRendersFailRow_forMissingProfile() throws Exception {
        // 实际发现：perf + tomcat 两个容器（均成功）
        String perf = "[{\"benchmark\":\"io.springperf.benchmark.servlet.PerfBenchmark.json\","
                + "\"mode\":\"thrpt\",\"primaryMetric\":{\"score\":1234.0}}]";
        String tomcat = "[{\"benchmark\":\"io.springperf.benchmark.servlet.TomcatBenchmark.json\","
                + "\"mode\":\"thrpt\",\"primaryMetric\":{\"score\":2000.0}}]";
        Files.write(tempDir.resolve("jmh-results-perf.json"), perf.getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("jmh-results-tomcat.json"), tomcat.getBytes(StandardCharsets.UTF_8));

        // 期望容器含 undertow（整容器缺失）
        List<String> expectedProfiles = Arrays.asList("perf", "tomcat", "undertow");

        String report = invokeGenerateReport(tempDir, "mode=thrpt", expectedProfiles);

        // 摘要：缺失组合计入失败（2 成功 / 3 总数，1 失败）
        assertTrue(report.contains("总计 **2/3** 成功，**1** 失败"),
                "缺失 profile×api 应计入 effectiveFail。实际摘要: " + summaryLine(report));
        // 表格：缺失 profile 必须渲染 FAIL 列，与摘要分母一致（修复前无 undertow 列，此处 FAIL 不存在）
        assertTrue(report.contains("| json | 1234 | 2000 | FAIL |"),
                "表格应渲染缺失 profile 的 FAIL 列（表格 FAIL 数 = effectiveFail）");
    }

    /**
     * 回归 P2（diff 专项）：多线程伸缩性报告期望容器整容器缺失时，主表格必须渲染
     * 该容器的 FAIL 行，与摘要的缺失统计自洽。修复前 generateScalabilityReport 仅遍历
     * 实际发现的 profiles，缺失容器既无表格行也不标 FAIL，摘要却报缺失 → 自相矛盾
     * （对齐单线程 generateReport 的 tableProfiles 修法）。
     */
    @Test
    void generateScalabilityReport_tableRendersFailRow_forMissingProfile() throws Exception {
        // threads-1/jdk-17 下实际发现 perf + tomcat 两个容器（均成功）
        Path threads1 = tempDir.resolve("threads-1");
        Path jdk17 = threads1.resolve("jdk-17");
        Files.createDirectories(jdk17);
        String perf = "[{\"benchmark\":\"io.springperf.benchmark.servlet.PerfBenchmark.json\","
                + "\"mode\":\"thrpt\",\"primaryMetric\":{\"score\":1234.0}}]";
        String tomcat = "[{\"benchmark\":\"io.springperf.benchmark.servlet.TomcatBenchmark.json\","
                + "\"mode\":\"thrpt\",\"primaryMetric\":{\"score\":2000.0}}]";
        Files.write(jdk17.resolve("jmh-results-perf.json"), perf.getBytes(StandardCharsets.UTF_8));
        Files.write(jdk17.resolve("jmh-results-tomcat.json"), tomcat.getBytes(StandardCharsets.UTF_8));

        // 期望容器含 undertow（整容器缺失）
        List<String> expectedProfiles = Arrays.asList("perf", "tomcat", "undertow");

        String report = invokeGenerateScalabilityReport(tempDir,
                Collections.singletonList(threads1), "mode=thrpt", expectedProfiles);

        // 摘要：报告 3 个容器且缺失 1 个
        assertTrue(report.contains("缺失 1 个"), "摘要应声明缺失容器数");
        assertTrue(report.contains("⚠️ 缺失容器:"), "摘要应列出缺失容器");
        // 表格：缺失容器 undertow 必须渲染 FAIL 行（修复前无 undertow 行）
        assertTrue(report.contains("| undertow | 17 | FAIL |"),
                "并发伸缩性表格应渲染缺失容器的 FAIL 行");
        // 已发现容器仍渲染数值
        assertTrue(report.contains("| perf | 17 | 1234 |"), "已发现容器应正常渲染");
    }

    private static String invokeGenerateScalabilityReport(Path runDir, List<Path> threadDirs, String runMeta,
                                                          List<String> expectedProfiles) throws Exception {
        Method m = ReportGenerator.class.getDeclaredMethod("generateScalabilityReport",
                Path.class, List.class, String.class, List.class);
        m.setAccessible(true);
        return (String) m.invoke(null, runDir, threadDirs, runMeta, expectedProfiles);
    }

    private static String invokeGenerateReport(Path jdkDir, String runMeta, List<String> expectedProfiles) throws Exception {
        Method m = ReportGenerator.class.getDeclaredMethod("generateReport", Path.class, String.class, List.class);
        m.setAccessible(true);
        return (String) m.invoke(null, jdkDir, runMeta, expectedProfiles);
    }

    private static String summaryLine(String report) {
        for (String line : report.split("\n")) {
            if (line.contains("总计")) return line;
        }
        return "(摘要行未找到)";
    }
}
