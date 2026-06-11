package io.springperf.benchmark.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring WebFlux + Netty 启动类。
 * 使用官方的 spring-boot-starter-webflux，内嵌 Reactor Netty。
 */
@SpringBootApplication(scanBasePackages = {"io.springperf.benchmark.controller.reactive"})
public class WebFluxApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebFluxApplication.class, args);
    }
}
