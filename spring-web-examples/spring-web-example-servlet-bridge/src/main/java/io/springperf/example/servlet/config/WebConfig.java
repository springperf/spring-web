package io.springperf.example.servlet.config;

import io.springperf.example.servlet.interceptor.MeasurementInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MeasurementInterceptor())
                .addPathPatterns("/bridge/**");
    }
}