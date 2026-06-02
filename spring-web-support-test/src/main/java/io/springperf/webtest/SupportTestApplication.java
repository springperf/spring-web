package io.springperf.webtest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"io.springperf.webtest"})
public class SupportTestApplication {
    public static void main(String[] args) throws Exception {
        SpringApplication.run(SupportTestApplication.class, args);
    }
}
