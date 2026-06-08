package io.springperf.webtest.proxy;

import io.springperf.web.filter.FilterChain;
import io.springperf.web.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 测试 WebFilter：为所有响应添加 X-Test-Filter 响应头。
 * 被 WebFilterRegistry 自动注册，验证 CGLIB 代理 Controller 的请求
 * 能正确经过 WebFilter 管线。
 */
@Component
@Order(1)
public class ProxyTestFilter implements WebFilter {

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
        response.getHeaders().add("X-Test-Filter", "executed");
        chain.doFilter(request, response);
    }
}