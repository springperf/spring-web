package io.springperf.benchmark.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring MVC + Undertow 启动类。
 * 排除 Tomcat，引入 Undertow。
 */
@SpringBootApplication(scanBasePackages = {"io.springperf.benchmark.controller"})
public class UndertowApplication {

    public static void main(String[] args) {
        SpringApplication.run(UndertowApplication.class, args);
    }
}
