package io.springperf.web.support.mvc.interceptor;

import io.springperf.web.core.interceptor.InterceptorRegistration;
import io.springperf.web.core.interceptor.InterceptorRegistry;
import org.springframework.core.annotation.AnnotationAwareOrderUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;

public class SupportInterceptorRegistry extends InterceptorRegistry {

    public SupportInterceptorRegistry() {
        super();
        autoRegisterWebComponent(org.springframework.web.servlet.config.annotation.InterceptorRegistration.class, this::convert);
        autoRegisterWebComponent(HandlerInterceptor.class, this::convert);
    }

    public InterceptorRegistration convert(org.springframework.web.servlet.config.annotation.InterceptorRegistration registration) {
        HandlerInterceptorWrapper handlerInterceptorWrapper = new HandlerInterceptorWrapper(registration.getInterceptor());
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
            interceptorRegistration.addPathPatterns(mappedInterceptor.getPathPatterns());
            interceptorRegistration.excludePathPatterns(mappedInterceptor.getExcludePatterns());
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
