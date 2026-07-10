package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.WebFluxApplication;

public class WebFluxBenchmark extends AbstractServerBenchmark {
    @Override
    protected Class<?> getApplicationClass() {
        return WebFluxApplication.class;
    }
}