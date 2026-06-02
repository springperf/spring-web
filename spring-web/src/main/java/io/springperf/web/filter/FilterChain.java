package io.springperf.web.filter;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

public interface FilterChain {
    void doFilter(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;
}