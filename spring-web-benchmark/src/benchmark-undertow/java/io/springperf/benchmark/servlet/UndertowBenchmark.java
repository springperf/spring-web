package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.UndertowApplication;

public class UndertowBenchmark extends AbstractServerBenchmark {
    @Override
    protected Class<?> getApplicationClass() {
        return UndertowApplication.class;
    }
}