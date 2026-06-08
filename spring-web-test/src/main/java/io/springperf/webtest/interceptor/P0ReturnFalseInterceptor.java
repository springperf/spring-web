package io.springperf.webtest.interceptor;

import io.springperf.web.core.interceptor.HandlerInterceptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

/**
 * 测试 Interceptor preHandle 返回 false 的场景。
 * 通过 InterceptorRegistration 的 addPathPatterns 控制仅对特定路径生效。
 * 被调用时写入空 body 并返回 false，避免 flushed 时 chunked 编码挂起。
 */
public class P0ReturnFalseInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(ServerHttpRequest request, ServerHttpResponse response, Object handler) throws Exception {
        response.setStatusCode(HttpStatus.OK);
        response.getBody().write(new byte[0]);
        return false;
    }
}
