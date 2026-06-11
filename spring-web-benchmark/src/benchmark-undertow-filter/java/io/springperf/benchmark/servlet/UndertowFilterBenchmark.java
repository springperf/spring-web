package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.UndertowFilterApplication;

public class UndertowFilterBenchmark extends AbstractServerBenchmark {
    @Override
    protected Class<?> getApplicationClass() {
        return UndertowFilterApplication.class;
    }
}