package io.springperf.webtest.config;

import io.springperf.web.core.interceptor.InterceptorRegistration;
import io.springperf.webtest.interceptor.P0ReturnFalseInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class P0TestConfig {

    @Bean
    public InterceptorRegistration p0ReturnFalseInterceptorRegistration() {
        return new InterceptorRegistration(new P0ReturnFalseInterceptor())
                .addPathPatterns("/**")
                .order(300);
    }
}