package io.springperf.web.support.servlet.filter;

import io.springperf.web.core.filter.FilterChain;
import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;

public class FilterWrapper implements WebFilter {

    protected jakarta.servlet.Filter filter;

    protected int order;

    public FilterWrapper(jakarta.servlet.Filter filter) {
        this.filter = filter;
        this.order = WebFilter.defaultOrder;
    }

    public FilterWrapper(jakarta.servlet.Filter filter, int order) {
        this.filter = filter;
        this.order = order;
    }

    public int getOrder() {
        return order;
    }

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
        doFilterInternal(request, response, chain);
    }

    protected void doFilterInternal(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
        ServletAdapterContext adapterContext = ServletAttribute.getAdapterContext(request.getRequestContext());
        if (adapterContext == null) {
            adapterContext = createServletAdapterContext(request, response, chain);
            ServletAttribute.setAdapterContext(request.getRequestContext(), adapterContext);
        }
        filter.doFilter(adapterContext.getRequest(), adapterContext.getResponse(), adapterContext.getFilterChain());
    }

    protected ServletAdapterContext createServletAdapterContext(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) {
        PerfHttpServletRequest restRequest = new PerfHttpServletRequest(request);
        PerfHttpServletResponse restResponse = new PerfHttpServletResponse(response);
        PerfHttpServletFilterChain filterChain = new PerfHttpServletFilterChain(request, response, chain);
        return new ServletAdapterContext(restRequest, restResponse, filterChain);
    }

    @Override
    public String getComponentName() {
        return filter.getClass().getName();
    }

    @Override
    public String toString() {
        return filter.toString();
    }
}