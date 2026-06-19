package io.springperf.web.support.servlet.filter;

import io.springperf.web.filter.FilterChain;
import io.springperf.web.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;

public class FilterWrapper implements WebFilter {

    protected javax.servlet.Filter filter;

    protected int order;

    public FilterWrapper(javax.servlet.Filter filter) {
        this.filter = filter;
        this.order = WebFilter.defaultOrder;
    }

    /**
     * Create a FilterWrapper with optional path-based matching.
     * <p>Returns a plain {@link FilterWrapper} when no path rules are given
     * (avoiding match overhead), or a {@link MatchFilterWrapper} when
     * path rules are present.</p>
     *
     * @param filter             the servlet filter to wrap
     * @param supportedPathRules URL path patterns, may be {@code null} or empty
     * @param order              the filter order
     * @return a FilterWrapper (with or without path matching)
     */
    public static FilterWrapper create(javax.servlet.Filter filter, String[] supportedPathRules, int order) {
        if (supportedPathRules == null || supportedPathRules.length == 0) {
            FilterWrapper w = new FilterWrapper(filter);
            w.order = order;
            return w;
        }
        return new MatchFilterWrapper(filter, supportedPathRules, order);
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