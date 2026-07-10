package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.TomcatApplication;

public class TomcatBenchmark extends AbstractServerBenchmark {
    @Override
    protected Class<?> getApplicationClass() {
        return TomcatApplication.class;
    }
}