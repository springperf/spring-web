package io.springperf.benchmark.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring MVC + Tomcat 启动类。
 * 使用官方的 spring-boot-starter-web（Spring MVC），内嵌 Tomcat。
 */
@SpringBootApplication(scanBasePackages = {"io.springperf.benchmark.controller"})
public class TomcatApplication {

    public static void main(String[] args) {
        SpringApplication.run(TomcatApplication.class, args);
    }
}
