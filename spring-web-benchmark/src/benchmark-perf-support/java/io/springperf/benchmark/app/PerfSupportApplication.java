package io.springperf.benchmark.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"io.springperf.benchmark.controller"})
public class PerfSupportApplication {

    public static void main(String[] args) {
        SpringApplication.run(PerfSupportApplication.class, args);
    }
}