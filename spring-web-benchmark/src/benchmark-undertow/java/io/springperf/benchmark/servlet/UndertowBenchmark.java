package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.UndertowApplication;

/**
 * Spring MVC + Undertow JMH 基准测试。
 */
public class UndertowBenchmark extends AbstractServerBenchmark {

    @Override
    protected Class<?> getApplicationClass() {
        return UndertowApplication.class;
    }
}