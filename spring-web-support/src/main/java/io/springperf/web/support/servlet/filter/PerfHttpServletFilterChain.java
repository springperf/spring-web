package io.springperf.web.support.servlet.filter;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import lombok.SneakyThrows;

public class PerfHttpServletFilterChain implements FilterChain {

    private final WebServerHttpRequest request;
    private final WebServerHttpResponse response;
    private io.springperf.web.core.filter.FilterChain filterChain;

    public PerfHttpServletFilterChain(WebServerHttpRequest request, WebServerHttpResponse response, io.springperf.web.core.filter.FilterChain filterChain) {
        this.request = request;
        this.response = response;
        this.filterChain = filterChain;
    }

    @Override
    @SneakyThrows
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
        filterChain.doFilter(request, response);
    }
}