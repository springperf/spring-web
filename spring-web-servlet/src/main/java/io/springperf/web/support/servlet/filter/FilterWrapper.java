package io.springperf.web.support.servlet.filter;

import io.springperf.web.context.LifecycleWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.filter.FilterChain;
import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.PerfServletContext;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class FilterWrapper implements WebFilter, LifecycleWebComponent {

    protected jakarta.servlet.Filter filter;

    protected int order;

    private volatile boolean initialized;

    /** 动态注册时显式给定的组件名（{@code null} → 按实例身份自动分配）。 */
    private final String explicitName;

    /** 动态注册时显式给定的 init parameters（{@code null} → 从 {@code @WebFilter} 解析）。 */
    private final Map<String, String> explicitInitParams;

    public FilterWrapper(jakarta.servlet.Filter filter) {
        this(filter, WebFilter.defaultOrder, null, null);
    }

    public FilterWrapper(jakarta.servlet.Filter filter, int order) {
        this(filter, order, null, null);
    }

    /**
     * 动态注册（{@code ServletContext#addFilter}）用构造器：组件名与 init-param 来自 {@code FilterRegistration}，而不是注解。
     * <p>
     * 动态项恒追加到链尾（Phase3 之后不再排序），因此 order 取默认值，不再暴露"可调顺序"的假象。
     * </p>
     */
    public FilterWrapper(jakarta.servlet.Filter filter, String componentName, Map<String, String> initParams) {
        this(filter, WebFilter.defaultOrder, componentName, initParams);
    }

    /**
     * 动态注册（{@code ServletContext#addFilter}）用构造器：组件名与 init-param 来自 {@code FilterRegistration}，而不是注解。
     */
    public FilterWrapper(jakarta.servlet.Filter filter, int order, String componentName,
            Map<String, String> initParams) {
        this.filter = filter;
        this.order = order;
        this.explicitName = componentName;
        this.explicitInitParams = initParams == null ? null
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(initParams));
    }

    @Override
    public void initWithWebContext(WebContext webContext) {
        if (!initialized) {
            initialized = true;
            PerfServletContext servletCtx = webContext.getWebComponent(PerfServletContext.class);
            String filterName = getComponentName();
            jakarta.servlet.FilterConfig filterConfig = new PerfFilterConfig(filterName, servletCtx,
                    resolveInitParams());
            try {
                filter.init(filterConfig);
            } catch (jakarta.servlet.ServletException e) {
                throw new RuntimeException("Failed to init filter: " + filterName, e);
            }
        }
    }

    /**
     * 解析 Filter 的 init-param：
     * <ol>
     * <li>从 {@link jakarta.servlet.annotation.WebFilter#initParams()} 读取</li>
     * <li>兜底空 Map</li>
     * </ol>
     */
    protected Map<String, String> resolveInitParams() {
        if (explicitInitParams != null) {
            return explicitInitParams;
        }
        jakarta.servlet.annotation.WebFilter webFilter = AnnotatedElementUtils.findMergedAnnotation(filter.getClass(),
                jakarta.servlet.annotation.WebFilter.class);
        if (webFilter == null) {
            return Collections.emptyMap();
        }
        jakarta.servlet.annotation.WebInitParam[] initParams = webFilter.initParams();
        if (initParams == null || initParams.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new java.util.HashMap<>(initParams.length);
        for (jakarta.servlet.annotation.WebInitParam param : initParams) {
            result.put(param.name(), param.value());
        }
        return result;
    }

    public int getOrder() {
        return order;
    }

    @Override
    public void destroyComponent() throws Exception {
        filter.destroy();
    }

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain)
            throws Exception {
        doFilterInternal(request, response, chain);
    }

    protected void doFilterInternal(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain)
            throws Exception {
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

    protected ServletAdapterContext createServletAdapterContext(WebServerHttpRequest request,
            WebServerHttpResponse response, FilterChain chain) {
        PerfHttpServletRequest restRequest = ServletAttribute.createPerfRequest(request);
        PerfHttpServletResponse restResponse = new PerfHttpServletResponse(response);
        PerfHttpServletFilterChain filterChain = new PerfHttpServletFilterChain(request, response, chain);
        ServletAdapterContext ctx = new ServletAdapterContext(restRequest, restResponse, filterChain);
        restResponse.setAdapterContext(ctx);
        return ctx;
    }

    // C4：实例级唯一标识。同类不同实例（不同 bean 名/order/urlPattern，如 Spring Security
    // 同 filter 类的多实例）不得被 WebComponentContainer 按类名去重误杀；
    // 同一 filter 实例重复包装时仍返回同一名字，容器按名去重（保留高 order）。
    // 修复前用 System.identityHashCode：同实例 hash 稳定（满足去重），但两个【不同】实例
    // 可能碰撞同一 hash，容器误判为同实例而静默销毁低 order 者（安全过滤器被丢 = 静默
    // 安全回归）。改为 IdentityHashMap 按实例身份分配唯一递增 ID——同实例同一 ID（去重
    // 语义不变），不同实例绝不碰撞。强引用随应用生命周期、filter 数量级极小，可忽略。
    private static final Map<jakarta.servlet.Filter, String> COMPONENT_NAMES = Collections
            .synchronizedMap(new IdentityHashMap<>());

    private static final AtomicLong NEXT_INSTANCE_ID = new AtomicLong(1);

    @Override
    public String getComponentName() {
        if (explicitName != null) {
            return explicitName;
        }
        return COMPONENT_NAMES.computeIfAbsent(filter,
                f -> f.getClass().getName() + "@" + NEXT_INSTANCE_ID.getAndIncrement());
    }

    @Override
    public String toString() {
        return filter.toString();
    }
}
