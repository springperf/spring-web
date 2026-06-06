package io.springperf.webtest.config;

import io.springperf.web.core.cors.CorsRegistration;
import io.springperf.web.core.interceptor.InterceptorRegistration;
import io.springperf.web.core.resource.ResourceHandlerRegistration;
import io.springperf.webtest.interceptor.LifecycleInterceptor;
import io.springperf.webtest.interceptor.LoginInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebTestConfig {

    @Bean
    public ResourceHandlerRegistration resourceHandlerRegistration() {
        return new ResourceHandlerRegistration("/static/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(3600);
    }

    @Bean
    public CorsRegistration corsRegistration() {
        return new CorsRegistration("/api/**")
                .allowedOrigins("http://example.com")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowCredentials(true)
                .maxAge(3600);
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
    public MethodArgumentNotValidResolver methodArgumentNotValidResolver() {
        return new MethodArgumentNotValidResolver();
    }
}
