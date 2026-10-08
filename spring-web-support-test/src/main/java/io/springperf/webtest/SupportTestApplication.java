package io.springperf.webtest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.springperf.web.core.metrics.CountingWebMetrics;
import io.springperf.web.core.metrics.WebMetrics;

@SpringBootApplication(scanBasePackages = { "io.springperf.webtest" })
public class SupportTestApplication {
    public static void main(String[] args) throws Exception {
        SpringApplication.run(SupportTestApplication.class, args);
    }

    /**
     * 可读计量实现：E2E 断言「异步生命周期归零」需要它。默认装配是 {@code NoOpWebMetrics}（零开销）， 读不出计数；容器 bean 优先于默认值，故本 context 的计数落在这一份实例上。
     */
    @Bean
    WebMetrics countingWebMetrics() {
        return new CountingWebMetrics();
    }
}
