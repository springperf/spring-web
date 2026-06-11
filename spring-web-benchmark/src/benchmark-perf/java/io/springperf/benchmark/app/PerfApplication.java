package io.springperf.benchmark.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 自定义框架 (Perf Netty) 启动类。
 * 激活 spring-boot-starter-web（本项目的 starter），自动配置 Netty 服务器。
 */
@SpringBootApplication(scanBasePackages = {"io.springperf.benchmark.controller"})
public class PerfApplication {

    public static void main(String[] args) {
        SpringApplication.run(PerfApplication.class, args);
    }
}
