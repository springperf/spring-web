package io.springperf.webtest.filter;

import io.springperf.web.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class TestWebFilter implements WebFilter {

    public static final String HEADER_NAME = "X-Test-Filter";
    public static final String HEADER_VALUE = "applied";

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response,
                         io.springperf.web.filter.FilterChain chain) throws Exception {
        // Set header before chain to ensure it's included in the response
        response.getHeaders().add(HEADER_NAME, HEADER_VALUE);
        chain.doFilter(request, response);
    }
}