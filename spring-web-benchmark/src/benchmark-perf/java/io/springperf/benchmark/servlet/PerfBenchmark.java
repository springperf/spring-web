package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.PerfApplication;

/**
 * Perf (Netty 自定义框架) JMH 基准测试。
 */
public class PerfBenchmark extends AbstractServerBenchmark {

    @Override
    protected Class<?> getApplicationClass() {
        return PerfApplication.class;
    }
}