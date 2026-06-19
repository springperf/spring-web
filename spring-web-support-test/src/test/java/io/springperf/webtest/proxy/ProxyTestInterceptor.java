package io.springperf.webtest.proxy;

import io.springperf.web.core.interceptor.HandlerInterceptor;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.stereotype.Component;

/**
 * 测试拦截器：为所有请求添加 X-Test-Interceptor 响应头。
 * <p>
 * 被 InterceptorRegistry 自动注册为全局拦截器，
 * 用于验证 CGLIB 代理 Controller 的请求仍能正确经过拦截器管线。
 */
@Component
public class ProxyTestInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(WebServerHttpRequest request, WebServerHttpResponse response, Object handler) throws Exception {
        response.getHeaders().add("X-Test-Interceptor", "called");
        return true;
    }
}