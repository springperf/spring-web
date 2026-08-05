package io.springperf.benchmark.config;

import io.springperf.benchmark.controller.BenchmarkController;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * perf profile 专属组件扫描：排除共享 {@link BenchmarkController}（echo 无 @Optimize），
 * 由 {@code PerfBenchmarkController} 接管 /demo/echo（echo 带 @Optimize），
 * 避免同路径双映射歧义。PerfSseController 等其他控制器不受影响。
 * <p>
 * 排除必须用 REGEX 精确匹配 BenchmarkController：ASSIGNABLE_TYPE 会连带匹配其子类
 * （PerfBenchmarkController extends BenchmarkController），导致 perf 控制器被整个排除。
 */
@Configuration
@ComponentScan(basePackages = "io.springperf.benchmark.controller",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "io\\.springperf\\.benchmark\\.controller\\.BenchmarkController"))
public class PerfControllerScanConfig {
}
