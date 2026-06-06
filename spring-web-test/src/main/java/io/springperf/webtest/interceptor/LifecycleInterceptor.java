package io.springperf.webtest.interceptor;

import io.springperf.web.core.interceptor.HandlerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

public class LifecycleInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LifecycleInterceptor.class);

    public static volatile int postHandleCount = 0;
    public static volatile int afterCompletionCount = 0;

    public static void resetCounts() {
        postHandleCount = 0;
        afterCompletionCount = 0;
    }

    @Override
    public boolean preHandle(ServerHttpRequest request, ServerHttpResponse response, Object handler) throws Exception {
        return true;
    }

    @Override
    public void postHandle(ServerHttpRequest request, ServerHttpResponse response, Object handler, Object result) throws Exception {
        log.info("LifecycleInterceptor.postHandle called");
        postHandleCount++;
    }

    @Override
    public void afterCompletion(ServerHttpRequest request, ServerHttpResponse response, Object handler, Throwable ex) throws Exception {
        log.info("LifecycleInterceptor.afterCompletion called");
        afterCompletionCount++;
    }
}