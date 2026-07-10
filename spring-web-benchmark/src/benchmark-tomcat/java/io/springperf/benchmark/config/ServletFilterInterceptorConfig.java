package io.springperf.benchmark.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
public class ServletFilterInterceptorConfig implements WebMvcConfigurer {

    // 5 pass-through Filter
    @Bean
    public Filter servletFilter1() { return passThroughFilter(); }

    @Bean
    public Filter servletFilter2() { return passThroughFilter(); }

    @Bean
    public Filter servletFilter3() { return passThroughFilter(); }

    @Bean
    public Filter servletFilter4() { return passThroughFilter(); }

    @Bean
    public Filter servletFilter5() { return passThroughFilter(); }

    private static Filter passThroughFilter() {
        return new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                chain.doFilter(req, res);
            }
            @Override
            public void init(FilterConfig cfg) {}
            @Override
            public void destroy() {}
        };
    }

    // 3 pass-through Interceptor
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PassThroughInterceptor()).addPathPatterns("/**");
        registry.addInterceptor(new PassThroughInterceptor()).addPathPatterns("/**");
        registry.addInterceptor(new PassThroughInterceptor()).addPathPatterns("/**");
    }

    private static class PassThroughInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
            return true;
        }
        @Override
        public void postHandle(HttpServletRequest req, HttpServletResponse res, Object handler, ModelAndView modelAndView) {}
        @Override
        public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {}
    }
}