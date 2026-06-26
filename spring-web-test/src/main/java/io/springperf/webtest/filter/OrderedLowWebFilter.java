package io.springperf.webtest.filter;

import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * P2 test: Ordered WebFilter (Order=low) that adds a header to verify filter chain order.
 * Used together with OrderedHighWebFilter to verify that filters execute in order.
 */
@Component
@Order(5)
public class OrderedLowWebFilter implements WebFilter {

    public static final String HEADER_NAME = "X-Order-Low";

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response,
                         io.springperf.web.core.filter.FilterChain chain) throws Exception {
        response.getHeaders().add(HEADER_NAME, "executed");
        chain.doFilter(request, response);
    }
}
