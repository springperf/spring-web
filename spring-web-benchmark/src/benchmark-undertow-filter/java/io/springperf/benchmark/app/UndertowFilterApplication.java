package io.springperf.benchmark.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "io.springperf.benchmark.controller",
        "io.springperf.benchmark.config"
})
public class UndertowFilterApplication {
    public static void main(String[] args) {
        SpringApplication.run(UndertowFilterApplication.class, args);
    }
}