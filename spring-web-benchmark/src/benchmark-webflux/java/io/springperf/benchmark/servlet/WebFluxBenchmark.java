package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.WebFluxApplication;

/**
 * Spring WebFlux + Reactor Netty JMH 基准测试。
 */
public class WebFluxBenchmark extends AbstractServerBenchmark {

    @Override
    protected Class<?> getApplicationClass() {
        return WebFluxApplication.class;
    }
}