package io.springperf.web.support.mvc.interceptor;

import io.springperf.web.core.interceptor.HandlerInterceptor;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.util.NestedServletException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class HandlerInterceptorWrapper implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(HandlerInterceptorWrapper.class);

    private final org.springframework.web.servlet.HandlerInterceptor interceptor;

    public HandlerInterceptorWrapper(org.springframework.web.servlet.HandlerInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public boolean preHandle(WebServerHttpRequest request, WebServerHttpResponse response, Object handler) throws Exception {
        HttpServletRequest servletRequest = extractRequest(request);
        HttpServletResponse servletResponse = extractResponse(request);
        if (servletRequest == null || servletResponse == null) {
            log.warn("HandlerInterceptorWrapper.preHandle: Servlet request/response not available, skipping interceptor {}", interceptor);
            return true;
        }
        return interceptor.preHandle(servletRequest, servletResponse, handler);
    }

    @Override
    public void postHandle(WebServerHttpRequest request, WebServerHttpResponse response, Object handler, Object result) throws Exception {
        HttpServletRequest servletRequest = extractRequest(request);
        HttpServletResponse servletResponse = extractResponse(request);
        if (servletRequest == null || servletResponse == null) return;
        interceptor.postHandle(servletRequest, servletResponse, handler, null);
    }

    @Override
    public void afterCompletion(WebServerHttpRequest request, WebServerHttpResponse response, Object handler, Throwable ex) throws Exception {
        HttpServletRequest servletRequest = extractRequest(request);
        HttpServletResponse servletResponse = extractResponse(request);
        if (servletRequest == null || servletResponse == null) return;
        interceptor.afterCompletion(servletRequest, servletResponse, handler,
                ex instanceof Exception ? (Exception) ex : new NestedServletException("Handler dispatch failed", ex));
    }

    @Override
    public void afterConcurrentHandlingStarted(WebServerHttpRequest request, WebServerHttpResponse response, Object handler) throws Exception {
        if (interceptor instanceof AsyncHandlerInterceptor) {
            HttpServletRequest servletRequest = extractRequest(request);
            HttpServletResponse servletResponse = extractResponse(request);
            if (servletRequest == null || servletResponse == null) return;
            ((AsyncHandlerInterceptor) interceptor).afterConcurrentHandlingStarted(servletRequest, servletResponse, handler);
        }
    }

    private static HttpServletRequest extractRequest(WebServerHttpRequest request) {
        RequestContext ctx = request.getRequestContext();
        if (ctx != null) {
            HttpServletRequest sr = ServletAttribute.getRequest(ctx);
            if (sr != null) return sr;
        }
        ServletRequestAttributes attrs = (ServletRequestAttributes)
                RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }

    private static HttpServletResponse extractResponse(WebServerHttpRequest request) {
        RequestContext ctx = request.getRequestContext();
        if (ctx != null) {
            HttpServletResponse sr = ServletAttribute.getResponse(ctx);
            if (sr != null) return sr;
        }
        ServletRequestAttributes attrs = (ServletRequestAttributes)
                RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getResponse() : null;
    }

    @Override
    public String toString() {
        return "InterceptorWrapper(" + interceptor + ')';
    }
}
