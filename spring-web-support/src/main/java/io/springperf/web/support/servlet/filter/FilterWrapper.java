package io.springperf.web.support.servlet.filter;

import io.springperf.web.core.filter.FilterChain;
import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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

    // C4：实例级唯一标识。同类不同实例（不同 bean 名/order/urlPattern，如 Spring Security
    // 同 filter 类的多实例）不得被 WebComponentContainer 按类名去重误杀；
    // 同一 filter 实例重复包装时仍返回同一名字，容器按名去重（保留高 order）。
    // 修复前用 System.identityHashCode：同实例 hash 稳定（满足去重），但两个【不同】实例
    // 可能碰撞同一 hash，容器误判为同实例而静默销毁低 order 者（安全过滤器被丢 = 静默
    // 安全回归）。改为 IdentityHashMap 按实例身份分配唯一递增 ID——同实例同一 ID（去重
    // 语义不变），不同实例绝不碰撞。强引用随应用生命周期、filter 数量级极小，可忽略。
    private static final Map<jakarta.servlet.Filter, String> COMPONENT_NAMES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private static final AtomicLong NEXT_INSTANCE_ID = new AtomicLong(1);

    @Override
    public String getComponentName() {
        return COMPONENT_NAMES.computeIfAbsent(filter, f ->
                f.getClass().getName() + "@" + NEXT_INSTANCE_ID.getAndIncrement());
    }

    @Override
    public String toString() {
        return filter.toString();
    }
}