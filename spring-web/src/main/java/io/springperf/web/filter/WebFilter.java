package io.springperf.web.filter;

import io.springperf.web.context.WebComponent;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

public interface WebFilter extends WebComponent {

    int defaultOrder = 0;

    void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception;

    @Override
    default int getOrder() {
        return defaultOrder;
    }
}
