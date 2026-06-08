package io.springperf.web.core.interceptor;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

/**
 * Contract for intercepting HTTP request processing at the handler level.
 *
 * <p>Handler interceptors provide fine-grained hooks around controller method
 * invocation: before the handler runs ({@link #preHandle}), after the handler
 * returns ({@link #postHandle}), after the request completes
 * ({@link #afterCompletion}), and when asynchronous processing starts
 * ({@link #afterConcurrentHandlingStarted}).</p>
 *
 * <p>Unlike {@link io.springperf.web.filter.WebFilter}, interceptors operate
 * within the dispatcher pipeline and have access to the resolved handler
 * method. They are the preferred mechanism for cross-cutting concerns such
 * as authentication checks, logging, and locale resolution.</p>
 *
 * @since 1.0.0
 * @see io.springperf.web.filter.WebFilter
 */
public interface HandlerInterceptor {
    /**
     * 在 Controller 执行前调用
     * 返回 false 则中断执行
     *
     * @param request  the server HTTP request
     * @param response the server HTTP response
     * @param handler  the handler method for the request
     * @return {@code true} to continue processing, {@code false} to abort
     * @throws Exception if pre-processing fails
     */
    default boolean preHandle(ServerHttpRequest request, ServerHttpResponse response, Object handler) throws Exception {
        return true;
    }

    /**
     * Controller 执行后调用（在处理参数前）
     *
     * @param request  the server HTTP request
     * @param response the server HTTP response
     * @param handler  the handler method for the request
     * @param result   the return value from the handler (may be {@code null})
     * @throws Exception if post-processing fails
     */
    default void postHandle(ServerHttpRequest request, ServerHttpResponse response, Object handler, Object result) throws Exception {
    }

    /**
     * 无论是否异常，都会在请求完成后执行
     *
     * @param request  the server HTTP request
     * @param response the server HTTP response
     * @param handler  the handler method for the request
     * @param ex       the exception that occurred, or {@code null} if none
     * @throws Exception if completion processing fails
     */
    default void afterCompletion(ServerHttpRequest request, ServerHttpResponse response, Object handler, Throwable ex) throws Exception {
    }

    /**
     * 开启异步处理时调用
     *
     * @param request  the server HTTP request
     * @param response the server HTTP response
     * @param handler  the handler method for the request
     * @throws Exception if the callback fails
     */
    default void afterConcurrentHandlingStarted(ServerHttpRequest request, ServerHttpResponse response, Object handler) throws Exception {
    }
}
