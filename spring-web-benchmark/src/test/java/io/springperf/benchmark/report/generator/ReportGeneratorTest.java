package io.springperf.benchmark.report.generator;

import io.springperf.benchmark.report.generator.ReportGenerator.ProfileData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
