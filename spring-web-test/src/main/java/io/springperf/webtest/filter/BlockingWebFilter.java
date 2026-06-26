package io.springperf.webtest.filter;

import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * P2 test: WebFilter that blocks the request chain with a specific status code.
 */
@Component
@Order(30)
public class BlockingWebFilter implements WebFilter {

    public static final String HEADER_NAME = "X-Blocking-Filter";
    public static final String HEADER_VALUE = "blocked";

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response,
                         io.springperf.web.core.filter.FilterChain chain) throws Exception {
        // Only block requests to the specific path
        String path = request.getPath();
        if (path.contains("/p2/blocked")) {
            response.getHeaders().add(HEADER_NAME, HEADER_VALUE);
            response.sendError(HttpStatus.FORBIDDEN, "{\"error\":\"blocked by filter\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
