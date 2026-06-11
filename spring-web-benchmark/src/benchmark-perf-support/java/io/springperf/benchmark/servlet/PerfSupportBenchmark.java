package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.PerfSupportApplication;

public class PerfSupportBenchmark extends AbstractServerBenchmark {
    @Override
    protected Class<?> getApplicationClass() {
        return PerfSupportApplication.class;
    }
}