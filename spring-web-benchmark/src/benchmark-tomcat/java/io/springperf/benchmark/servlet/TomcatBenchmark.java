package io.springperf.benchmark.servlet;

import io.springperf.benchmark.app.TomcatApplication;

/**
 * Spring MVC + Tomcat JMH 基准测试。
 */
public class TomcatBenchmark extends AbstractServerBenchmark {

    @Override
    protected Class<?> getApplicationClass() {
        return TomcatApplication.class;
    }
}