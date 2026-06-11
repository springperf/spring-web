package io.springperf.benchmark.config;

import io.springperf.web.core.interceptor.HandlerInterceptor;
import io.springperf.web.core.interceptor.InterceptorRegistration;
import io.springperf.web.filter.WebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

@Configuration
public class PerfFilterConfig {

    // 5 pass-through WebFilter
    @Bean
    public WebFilter perfFilter1() { return passThroughFilter(); }

    @Bean
    public WebFilter perfFilter2() { return passThroughFilter(); }

    @Bean
    public WebFilter perfFilter3() { return passThroughFilter(); }

    @Bean
    public WebFilter perfFilter4() { return passThroughFilter(); }

    @Bean
    public WebFilter perfFilter5() { return passThroughFilter(); }

    private static WebFilter passThroughFilter() {
        return (request, response, chain) -> chain.doFilter(request, response);
    }

    // 3 pass-through HandlerInterceptor
    @Bean
    public InterceptorRegistration perfInterceptor1() {
        return interceptorReg();
    }

    @Bean
    public InterceptorRegistration perfInterceptor2() {
        return interceptorReg();
    }

    @Bean
    public InterceptorRegistration perfInterceptor3() {
        return interceptorReg();
    }

    private static InterceptorRegistration interceptorReg() {
        return new InterceptorRegistration(new PassThroughInterceptor()).addPathPatterns("/**");
    }

    private static class PassThroughInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(ServerHttpRequest req, ServerHttpResponse res, Object handler) {
            return true;
        }
        @Override
        public void postHandle(ServerHttpRequest req, ServerHttpResponse res, Object handler, Object result) {}
        @Override
        public void afterCompletion(ServerHttpRequest req, ServerHttpResponse res, Object handler, Throwable ex) {}
    }
}