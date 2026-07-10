package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.PerfApplication;

public class PerfBenchmark extends AbstractServerBenchmark {
    @Override
    protected Class<?> getApplicationClass() {
        return PerfApplication.class;
    }
}