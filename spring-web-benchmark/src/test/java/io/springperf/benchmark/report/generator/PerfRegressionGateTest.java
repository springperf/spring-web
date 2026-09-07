package io.springperf.benchmark.report.generator;

import io.springperf.benchmark.report.generator.ReportGenerator.ProfileData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P2 perf 回归门禁。
 * <p>与绝对数值基线不同，这里采用<b>相对断言</b>：同一运行目录内 perf（spring-web）
 * 与 tomcat 在相同机器、相同并发/JDK 下对跑，天然抵消硬件漂移，无需提交 JSON 基线文件
 * （benchmark-reports 已在 .gitignore 中）。
 * <p>仅分析<b>最新一次完整运行</b>（时间戳目录最大者），保证门禁反映当前代码状态；
 * 历史/旧版本运行的差异不代表回归，不参与判定。
 * <p>门禁规则（每个含 perf+tomcat 数据的 jdk 目录、每个 API）：
 * <ul>
 *   <li>吞吐：perf ≥ tomcat × 1.05（框架必须至少领先 tomcat 5%，防回归漏过）</li>
 *   <li>分配：perf 每请求分配 ≤ tomcat × 1.05（框架应不劣于 tomcat 的内存效率）</li>
 * </ul>
 * 无 perf+tomcat 对比数据的场景（如常规 `mvn test` 未跑 benchmark）自动跳过。
 */
class PerfRegressionGateTest {

    private static final double THROUGHPUT_TOLERANCE = 1.05;
    private static final double ALLOC_TOLERANCE = 1.05;

    @Test
    void perfMustNotRegressBehindTomcat_whereDataExists() throws Exception {
        List<Path> jdkDirs = findJdkDirsWithBothProfiles();
        assumeTrue(!jdkDirs.isEmpty(), "未找到 perf+tomcat 对比数据，跳过 perf 回归门禁（需先运行 benchmark）");

        StringBuilder failures = new StringBuilder();
        int checked = 0;
        for (Path jdkDir : jdkDirs) {
            Map<String, Map<String, ProfileData>> byApi = ReportGenerator.discoverAllData(jdkDir);
            for (Map.Entry<String, Map<String, ProfileData>> e : byApi.entrySet()) {
                String api = e.getKey();
                ProfileData perf = e.getValue().get("perf");
                ProfileData tomcat = e.getValue().get("tomcat");
                if (perf == null || tomcat == null) continue;

                double perfTp = firstThroughput(perf);
                double tomcatTp = firstThroughput(tomcat);
                if (perfTp <= 0 || tomcatTp <= 0) continue;
                checked++;

                if (perfTp < tomcatTp * THROUGHPUT_TOLERANCE) {
                    failures.append(String.format("  [%s] api=%s 吞吐回归: perf=%.0f ops/s < tomcat×%.2f=%.0f ops/s%n",
                            jdkDir, api, perfTp, THROUGHPUT_TOLERANCE, tomcatTp * THROUGHPUT_TOLERANCE));
                }
                if (perf.gcAllocRateNormBytes > 0 && tomcat.gcAllocRateNormBytes > 0
                        && perf.gcAllocRateNormBytes > tomcat.gcAllocRateNormBytes * ALLOC_TOLERANCE) {
                    failures.append(String.format("  [%s] api=%s 分配回归: perf=%.0f B/op > tomcat×%.2f=%.0f B/op%n",
                            jdkDir, api, perf.gcAllocRateNormBytes, ALLOC_TOLERANCE,
                            tomcat.gcAllocRateNormBytes * ALLOC_TOLERANCE));
                }
            }
        }

        assertTrue(checked > 0, "解析到 perf+tomcat 文件但未提取到任何可比较的吞吐数据");
        assertTrue(failures.length() == 0, "perf 回归门禁失败（spring-web 不应明显慢于或更耗内存于 tomcat）：\n" + failures);
    }

    /** 收集最新一次完整运行目录中同时含 jmh-results-perf-*.json 与 jmh-results-tomcat-*.json 的 jdk 目录 */
    private static List<Path> findJdkDirsWithBothProfiles() throws IOException {
        List<Path> dirs = new ArrayList<>();
        Path root = Paths.get("benchmark-reports").toAbsolutePath();
        if (!Files.isDirectory(root)) return dirs;

        // 只取最新一次完整运行（时间戳目录名最大者），排除 latest/jfr-cpu-hotspot-* 等非完整运行目录
        Path latestRun = Files.list(root)
                .filter(Files::isDirectory)
                .filter(d -> d.getFileName().toString().matches("\\d{8}-\\d{6}"))
                .sorted()
                .reduce((a, b) -> b)
                .orElse(null);
        if (latestRun == null) return dirs;

        Files.walk(latestRun).filter(Files::isDirectory).forEach(d -> {
            boolean hasPerf = false, hasTomcat = false;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "jmh-results-*.json")) {
                for (Path f : ds) {
                    String n = f.getFileName().toString();
                    if (n.startsWith("jmh-results-perf")) hasPerf = true;
                    else if (n.startsWith("jmh-results-tomcat")) hasTomcat = true;
                }
            } catch (IOException ignored) {
                // 目录不可读则跳过
            }
            if (hasPerf && hasTomcat) dirs.add(d);
        });
        return dirs;
    }

    private static double firstThroughput(ProfileData d) {
        return d.throughputs.isEmpty() ? 0 : d.throughputs.values().iterator().next();
    }
}