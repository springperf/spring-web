package io.springperf.webtest.filter;

import io.springperf.web.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * P2 test: Ordered WebFilter (Order=high) that adds a header to verify filter chain order.
 * Used together with OrderedLowWebFilter to verify that filters execute in order.
 */
@Component
@Order(100)
public class OrderedHighWebFilter implements WebFilter {

    public static final String HEADER_NAME = "X-Order-High";

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response,
                         io.springperf.web.filter.FilterChain chain) throws Exception {
        response.getHeaders().add(HEADER_NAME, "executed");
        chain.doFilter(request, response);
    }
}
