package io.springperf.benchmark.report.generator;

import java.nio.file.Path;

/**
 * GC 日志解析器接口。
 * 实现类通过 {@link #supports(Path)} 检测日志格式，通过 {@link #parse(Path)} 提取指标。
 */
public interface GcLogParser {

    /** 返回此解析器是否能处理指定日志文件 */
    boolean supports(Path logFile);

    /** 解析日志文件，返回 GC 指标 */
    GcMetrics parse(Path logFile);
}