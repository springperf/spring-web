package io.springperf.web.core.interceptor;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

public interface HandlerInterceptor {
    /**
     * 在 Controller 执行前调用
     * 返回 false 则中断执行
     */
    default boolean preHandle(ServerHttpRequest request, ServerHttpResponse response, Object handler) throws Exception {
        return true;
    }

    /**
     * Controller 执行后调用（在处理参数前）
     */
    default void postHandle(ServerHttpRequest request, ServerHttpResponse response, Object handler, Object result) throws Exception {
    }

    /**
     * 无论是否异常，都会在请求完成后执行
     */
    default void afterCompletion(ServerHttpRequest request, ServerHttpResponse response, Object handler, Throwable ex) throws Exception {
    }

    /**
     * 开启异步处理时调用
     */
    default void afterConcurrentHandlingStarted(ServerHttpRequest request, ServerHttpResponse response, Object handler) throws Exception {
    }
}
