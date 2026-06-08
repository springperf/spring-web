package io.springperf.webtest.proxy;

import io.springperf.web.filter.FilterChain;
import io.springperf.web.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * P3 test: WebFilter that blocks requests to /proxy-parent/blocked.
 * Verifies that filter chain can be interrupted in a CGLIB proxy environment.
 */
@Component
@Order(20)
public class BlockingProxyFilter implements WebFilter {

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response,
                         FilterChain chain) throws Exception {
        String path = request.getPath();
        if (path != null && path.contains("/proxy-parent/blocked")) {
            response.getHeaders().add("X-Blocking-Filter", "blocked");
            response.sendError(HttpStatus.FORBIDDEN, "{\"error\":\"blocked by proxy filter\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}