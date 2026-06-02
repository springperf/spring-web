package io.springperf.web.support.servlet.filter;

import io.springperf.web.filter.FilterChain;
import io.springperf.web.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import io.springperf.web.support.servlet.filter.match.PathMatch;

import java.util.List;

public class FilterWrapper implements WebFilter {

    private List<PathMatch> pathMatchList;

    private javax.servlet.Filter filter;

    private int order;

    public FilterWrapper(javax.servlet.Filter filter) {
        this(filter, null);
    }

    public FilterWrapper(javax.servlet.Filter filter, String[] supportedPathRules) {
        this.filter = filter;
        this.pathMatchList = PathMatch.create(supportedPathRules);
        this.order = WebFilter.defaultOrder;
    }

    public FilterWrapper(javax.servlet.Filter filter, String[] supportedPathRules, int order) {
        this(filter, supportedPathRules);
        this.order = order;
    }

    public int getOrder() {
        return order;
    }

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
        String uri = request.getPath();
        if (match(uri)) {
            doFilterInternal(request, response, chain);
        } else {
            chain.doFilter(request, response);
        }
    }

    private boolean match(String uri) {
        if (pathMatchList == null || pathMatchList.isEmpty()) {
            return true;
        }
        for (PathMatch pathMatch : pathMatchList) {
            if (pathMatch.match(uri)) {
                return true;
            }
        }
        return false;
    }

    protected void doFilterInternal(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
        ServletAdapterContext adapterContext = (ServletAdapterContext) request.getRequestContext().getAttribute(ServletAdapterContext.REQUEST_ATTRIBUTE_NAME);
        if (adapterContext == null) {
            adapterContext = createServletAdapterContext(request, response, chain);
            request.getRequestContext().setAttribute(ServletAdapterContext.REQUEST_ATTRIBUTE_NAME, adapterContext);
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
        if (pathMatchList == null || pathMatchList.isEmpty()) {
            return filter.toString();
        } else {
            return filter.toString() + " " + pathMatchList;
        }
    }
}
