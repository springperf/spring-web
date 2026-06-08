package io.springperf.webtest.filter;

import io.springperf.web.filter.FilterChain;
import io.springperf.web.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class OrderedWebTestFilter implements WebFilter {

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
        response.getHeaders().set("X-Web-Filter", "called");
        chain.doFilter(request, response);
    }

    @Override
    public int getOrder() {
        return 10;
    }
}