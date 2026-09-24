package io.springperf.web.support.mvc.interceptor;

import io.springperf.web.core.interceptor.InterceptorRegistration;
import io.springperf.web.core.interceptor.InterceptorRegistry;
import org.springframework.core.annotation.AnnotationAwareOrderUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;

public class SupportInterceptorRegistry extends InterceptorRegistry {

    public SupportInterceptorRegistry() {
        super();
        // 仅自动注册 InterceptorRegistration（来自 WebMvcConfigurer.addInterceptors 桥接）
        // 与 MappedInterceptor（Spring 自动探测、自带路径规则）两类。
        // 不再自动注册普通 HandlerInterceptor bean：与 Spring MVC 语义一致
        // （普通 HandlerInterceptor bean 不会全局生效，需经 addInterceptors 或 MappedInterceptor），
        // 同时避免同一实例经 bean 扫描与 addInterceptors 两路注册导致同名碰撞/重复执行。
        autoRegisterWebComponent(org.springframework.web.servlet.config.annotation.InterceptorRegistration.class,
                this::convert);
        autoRegisterWebComponent(org.springframework.web.servlet.handler.MappedInterceptor.class, this::convert);
    }

    public InterceptorRegistration convert(
            org.springframework.web.servlet.config.annotation.InterceptorRegistration registration) {
        HandlerInterceptorWrapper handlerInterceptorWrapper = new HandlerInterceptorWrapper(
                registration.getInterceptor());
        InterceptorRegistration interceptorRegistration = new InterceptorRegistration(handlerInterceptorWrapper);
        for (String includePattern : registration.getIncludePatterns()) {
            interceptorRegistration.addPathPatterns(includePattern);
        }
        for (String excludePattern : registration.getExcludePatterns()) {
            interceptorRegistration.excludePathPatterns(excludePattern);
        }
        interceptorRegistration.order(registration.getOrder());
        interceptorRegistration.pathMatcher(registration.getPathMatcher());
        return interceptorRegistration;
    }

    public InterceptorRegistration convert(HandlerInterceptor interceptor) {
        HandlerInterceptorWrapper handlerInterceptorWrapper;
        InterceptorRegistration interceptorRegistration;
        if (interceptor instanceof MappedInterceptor) {
            MappedInterceptor mappedInterceptor = (MappedInterceptor) interceptor;
            handlerInterceptorWrapper = new HandlerInterceptorWrapper(mappedInterceptor.getInterceptor());
            interceptorRegistration = new InterceptorRegistration(handlerInterceptorWrapper);
            // MappedInterceptor 的两参构造器（最常用写法）getExcludePatterns() 返回 null，
            // 直接传给 addPathPatterns/excludePathPatterns(String...) 会触发 Arrays.asList(null) NPE。
            // 对 null 数组做空值保护，避免标准写法 bean 在自动注册时启动崩溃。
            String[] includePatterns = mappedInterceptor.getPathPatterns();
            if (includePatterns != null) {
                interceptorRegistration.addPathPatterns(includePatterns);
            }
            String[] excludePatterns = mappedInterceptor.getExcludePatterns();
            if (excludePatterns != null) {
                interceptorRegistration.excludePathPatterns(excludePatterns);
            }
            interceptorRegistration.pathMatcher(mappedInterceptor.getPathMatcher());
        } else {
            handlerInterceptorWrapper = new HandlerInterceptorWrapper(interceptor);
            interceptorRegistration = new InterceptorRegistration(handlerInterceptorWrapper);
        }
        Integer order = AnnotationAwareOrderUtils.findOrder(interceptor);
        if (order != null) {
            interceptorRegistration.order(order);
        }
        return interceptorRegistration;
    }
}
