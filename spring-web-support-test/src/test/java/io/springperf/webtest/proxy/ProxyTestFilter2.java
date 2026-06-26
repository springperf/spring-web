package io.springperf.webtest.proxy;

import io.springperf.web.core.filter.FilterChain;
import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * P4 test: 第二个 WebFilter，@Order(2)，用于验证多 Filter 排序。
 * 与 {@link ProxyTestFilter}（@Order(1)）、{@link BlockingProxyFilter}（@Order(20)）一起，
 * 验证 Filter 链按 @Order 值升序执行。
 */
@Component
@Order(2)
public class ProxyTestFilter2 implements WebFilter {

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response,
                         FilterChain chain) throws Exception {
        response.getHeaders().add("X-Test-Filter2", "executed");
        chain.doFilter(request, response);
    }
}