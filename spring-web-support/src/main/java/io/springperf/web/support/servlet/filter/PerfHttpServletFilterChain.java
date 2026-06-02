package io.springperf.web.support.servlet.filter;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import lombok.SneakyThrows;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

public class PerfHttpServletFilterChain implements FilterChain {

    private final WebServerHttpRequest request;
    private final WebServerHttpResponse response;
    private io.springperf.web.filter.FilterChain filterChain;

    public PerfHttpServletFilterChain(WebServerHttpRequest request, WebServerHttpResponse response, io.springperf.web.filter.FilterChain filterChain) {
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