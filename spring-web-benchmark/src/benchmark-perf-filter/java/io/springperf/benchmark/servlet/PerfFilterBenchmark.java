package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.PerfFilterApplication;

public class PerfFilterBenchmark extends AbstractServerBenchmark {
    @Override
    protected Class<?> getApplicationClass() {
        return PerfFilterApplication.class;
    }
}