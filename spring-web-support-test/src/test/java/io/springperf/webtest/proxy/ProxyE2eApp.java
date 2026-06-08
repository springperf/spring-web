package io.springperf.webtest.proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication(scanBasePackages = "io.springperf.webtest.proxy")
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class ProxyE2eApp {
    public static void main(String[] args) {
        SpringApplication.run(ProxyE2eApp.class, args);
    }
}
