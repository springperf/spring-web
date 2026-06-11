package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.PerfSupportFilterApplication;

public class PerfSupportFilterBenchmark extends AbstractServerBenchmark {
    @Override
    protected Class<?> getApplicationClass() {
        return PerfSupportFilterApplication.class;
    }
}