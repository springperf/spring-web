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

    protected javax.servlet.Filter filter;

    protected int order;

    public FilterWrapper(javax.servlet.Filter filter) {
        this.filter = filter;
        this.order = WebFilter.defaultOrder;
    }

    public FilterWrapper(javax.servlet.Filter filter, int order) {
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
        } else if (adapterContext.getFilterChain() == null) {
            adapterContext.setFilterChain(new PerfHttpServletFilterChain(request, response, chain));
        }
        adapterContext.rebindFrameworkRequest(request);
        adapterContext.rebindFrameworkResponse(response);
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
        // C4：实例级唯一标识。同类不同实例（不同 bean 名/order/urlPattern，如 Spring Security
        // 同 filter 类的多实例）不得被 WebComponentContainer 按类名去重误杀；
        // 同一 filter 实例重复包装时 identityHashCode 相同，仍会被去重（保留高 order）。
        return filter.getClass().getName() + "@" + System.identityHashCode(filter);
    }

    @Override
    public String toString() {
        return filter.toString();
    }
}