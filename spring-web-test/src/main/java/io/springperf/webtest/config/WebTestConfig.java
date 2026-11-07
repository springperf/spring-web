package io.springperf.webtest.config;

import io.springperf.web.core.cors.CorsRegistration;
import io.springperf.web.core.interceptor.InterceptorRegistration;
import io.springperf.web.core.resource.ResourceHandlerRegistration;
import io.springperf.webtest.interceptor.LifecycleInterceptor;
import io.springperf.webtest.interceptor.LoginInterceptor;
import io.springperf.webtest.interceptor.P0ReturnFalseInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebTestConfig {

    @Bean
    public ResourceHandlerRegistration resourceHandlerRegistration() {
        return new ResourceHandlerRegistration("/static/**").addResourceLocations("classpath:/static/")
                .setCachePeriod(3600);
    }

    @Bean
    public CorsRegistration corsRegistration() {
        // 注册模式按映射路径规则匹配（不含 context-path，如 /core/bytes）：
        // 写成 /api/** 会因为首段被当成 "api" 而永不命中，使这份全局配置成为死配置。
        return new CorsRegistration("/**").allowedOrigins("http://example.com")
                .allowedMethods("GET", "POST", "PUT", "DELETE").allowCredentials(true).maxAge(3600);
    }

    @Bean
    public InterceptorRegistration interceptorRegistration() {
        return new InterceptorRegistration(new LoginInterceptor()).addPathPatterns("/**")
                .excludePathPatterns("/echo", "/login").order(100);
    }

    @Bean
    public InterceptorRegistration lifecycleInterceptorRegistration() {
        // Use a custom subclass to avoid component name conflict
        // with the LoginInterceptor's InterceptorRegistration
        return new InterceptorRegistration(new LifecycleInterceptor()) {
            @Override
            public String getComponentName() {
                return "LifecycleInterceptorRegistration";
            }
        }.addPathPatterns("/**").order(200);
    }

    @Bean
    public InterceptorRegistration webP0ReturnFalseInterceptorRegistration() {
        // Use a custom subclass to avoid component name conflict
        // with other InterceptorRegistration beans
        return new InterceptorRegistration(new P0ReturnFalseInterceptor()) {
            @Override
            public String getComponentName() {
                return "P0ReturnFalseInterceptorRegistration";
            }
        }.addPathPatterns("/p0/interceptor-return-false").order(300);
    }

    @Bean
    public MethodArgumentNotValidResolver methodArgumentNotValidResolver() {
        return new MethodArgumentNotValidResolver();
    }
}
