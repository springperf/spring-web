package io.springperf.web.core.filter;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.util.support.ContainmentResult;

import java.util.ArrayList;
import java.util.List;

public class WebFilterRegistry extends WebComponentContainer {

    protected final List<WebFilterRegistration> registrations = new ArrayList<>();

    /**
     * 预计算的全量 filter 列表（无路由匹配时使用）。
     * 无 include/exclude 的 filter 直接放入，有路径规则的包装为
     * {@link RuntimeMappingWebFilter} 由其在 doFilter 中运行时匹配。
     */
    private List<WebFilter> allFilters = new ArrayList<>();

    @FunctionalInterface
    public interface FilterChainTerminal {
        void doFilter(WebServerHttpRequest request, WebServerHttpResponse response,
                      MappingResult mappingResult) throws Exception;
    }

    public WebFilterRegistry() {
        autoRegisterWebComponent(WebFilterRegistration.class);
        autoRegisterWebComponent(WebFilter.class, this::wrapFilterToRegistration);
    }

    protected WebFilterRegistration wrapFilterToRegistration(WebFilter filter) {
        return new WebFilterRegistration(filter);
    }

    @Override
    public void initComponentPhase3() throws Exception {
        super.initComponentPhase3();
        initRealComponentList(registrations, WebFilterRegistration.class);
        initAllFilters();
    }

    /**
     * 预计算全量 filter 列表（无路由匹配时使用）。
     * 无路径规则的 filter 直接放入（永远匹配），
     * 有路径规则的包装为 {@link RuntimeMappingWebFilter} 运行时按请求路径匹配。
     */
    protected void initAllFilters() {
        allFilters.clear();
        List<WebFilter> filters = new ArrayList<>(registrations.size());
        for (WebFilterRegistration registration : registrations) {
            if (registration.getIncludePatterns().isEmpty() && registration.getExcludePatterns().isEmpty()) {
                filters.add(registration.getFilter());
            } else {
                filters.add(new RuntimeMappingWebFilter(registration));
            }
        }
        this.allFilters = filters;
    }

    /**
     * 执行 Filter 链，按请求路径运行时匹配。
     * <p>用于未匹配路由的 filter 链（如直接处理请求的 Servlet Filter 桥接），
     * terminal 接收 {@link MappingResult} 以感知路由结果。</p>
     *
     * @param request       the current HTTP request
     * @param response      the current HTTP response
     * @param mappingResult the routing result
     * @param terminal      the terminal callback after all filters
     */
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, MappingResult mappingResult,
                         FilterChainTerminal terminal) throws Exception {
        List<WebFilter> resolved = resolveFilters(mappingResult, request);
        DefaultFilterChain chain = new DefaultFilterChain(resolved, mappingResult, terminal);
        chain.doFilter(request, response);
    }

    /**
     * 三段式解析：按 PathMappingContext 缓存 + 运行时路径匹配。
     */
    protected List<WebFilter> resolveFilters(MappingResult mappingResult, WebServerHttpRequest request) {
        if (!mappingResult.isMatched()) {
            return allFilters;
        }
        PathMappingContext mappingContext = mappingResult.getMatchedContext();
        List<WebFilter> cached = mappingContext.getCachedFilters();
        if (cached != null) {
            return cached;
        }

        // double-checked locking
        synchronized (mappingContext) {
            cached = mappingContext.getCachedFilters();
            if (cached == null) {
                cached = initCachedFilters(mappingContext);
                mappingContext.setCachedFilters(cached);
            }
        }
        return cached;
    }

    /**
     * 编译期三段式推断：逐 registration 判断与 mapping pathRule 的包含关系。
     * ALWAYS → 直接加入；NEVER → 跳过；RUNTIME → 包装为 RuntimeMappingWebFilter。
     */
    protected List<WebFilter> initCachedFilters(PathMappingContext mappingContext) {
        String pathRule = mappingContext.getPathRule();
        List<WebFilter> filters = new ArrayList<>();
        for (WebFilterRegistration registration : registrations) {
            ContainmentResult result = registration.matchPathRuleToCached(pathRule);
            if (result == ContainmentResult.ALWAYS) {
                filters.add(registration.getFilter());
            } else if (result == ContainmentResult.RUNTIME) {
                filters.add(new RuntimeMappingWebFilter(registration));
            }
            // NEVER → skip
        }
        return filters;
    }
}
