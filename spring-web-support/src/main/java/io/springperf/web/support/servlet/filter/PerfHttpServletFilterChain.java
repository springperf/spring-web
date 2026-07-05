package io.springperf.web.support.servlet.filter;

import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
public class PerfHttpServletFilterChain implements FilterChain {

    private final WebServerHttpRequest request;
    private final WebServerHttpResponse response;
    private final io.springperf.web.core.filter.FilterChain filterChain;

    public PerfHttpServletFilterChain(WebServerHttpRequest request, WebServerHttpResponse response, io.springperf.web.core.filter.FilterChain filterChain) {
        this.request = request;
        this.response = response;
        this.filterChain = filterChain;
    }

    @Override
    @SneakyThrows
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
        RequestContext requestContext = request.getRequestContext();
        if (requestContext != null) {
            ServletAdapterContext adapterContext = ServletAttribute.getAdapterContext(requestContext);
            if (adapterContext != null) {
                if (servletRequest != adapterContext.getRequest()) {
                    adapterContext.setRequest((HttpServletRequest) servletRequest);
                }
                if (servletResponse != adapterContext.getResponse()) {
                    adapterContext.setResponse((HttpServletResponse) servletResponse);
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}