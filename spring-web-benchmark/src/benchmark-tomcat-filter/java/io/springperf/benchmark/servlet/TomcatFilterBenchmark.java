package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.TomcatFilterApplication;

public class TomcatFilterBenchmark extends AbstractServerBenchmark {
    @Override
    protected Class<?> getApplicationClass() {
        return TomcatFilterApplication.class;
    }
}