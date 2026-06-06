package io.springperf.web.support.mvc.interceptor;

import io.springperf.web.core.interceptor.HandlerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.util.NestedServletException;

public class HandlerInterceptorWrapper implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(HandlerInterceptorWrapper.class);

    private final org.springframework.web.servlet.HandlerInterceptor interceptor;

    public HandlerInterceptorWrapper(org.springframework.web.servlet.HandlerInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public boolean preHandle(ServerHttpRequest request, ServerHttpResponse response, Object handler) throws Exception {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            log.warn("HandlerInterceptorWrapper.preHandle: RequestAttributes is null, skipping interceptor {}", interceptor);
            return true;
        }
        boolean result = interceptor.preHandle(attributes.getRequest(), attributes.getResponse(), handler);
        log.info("HandlerInterceptorWrapper.preHandle: interceptor={}, handler={}, result={}",
                interceptor.getClass().getSimpleName(), handler, result);
        return result;
    }

    @Override
    public void postHandle(ServerHttpRequest request, ServerHttpResponse response, Object handler, Object result) throws Exception {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) return;
        interceptor.postHandle(attributes.getRequest(), attributes.getResponse(), handler, null);
    }

    @Override
    public void afterCompletion(ServerHttpRequest request, ServerHttpResponse response, Object handler, Throwable ex) throws Exception {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) return;
        interceptor.afterCompletion(attributes.getRequest(), attributes.getResponse(), handler, ex instanceof Exception ? (Exception) ex : new NestedServletException("Handler dispatch failed", ex));
    }

    @Override
    public void afterConcurrentHandlingStarted(ServerHttpRequest request, ServerHttpResponse response, Object handler) throws Exception {
        if (interceptor instanceof AsyncHandlerInterceptor) {
            AsyncHandlerInterceptor asyncInterceptor = (AsyncHandlerInterceptor) interceptor;
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) return;
            asyncInterceptor.afterConcurrentHandlingStarted(attributes.getRequest(), attributes.getResponse(), handler);
        }
    }

    @Override
    public String toString() {
        return "InterceptorWrapper(" + interceptor + ')';
    }
}
