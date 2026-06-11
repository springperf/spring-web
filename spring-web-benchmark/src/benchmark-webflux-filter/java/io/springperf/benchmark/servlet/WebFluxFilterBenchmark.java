package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.WebFluxFilterApplication;

public class WebFluxFilterBenchmark extends AbstractServerBenchmark {
    @Override
    protected Class<?> getApplicationClass() {
        return WebFluxFilterApplication.class;
    }
}