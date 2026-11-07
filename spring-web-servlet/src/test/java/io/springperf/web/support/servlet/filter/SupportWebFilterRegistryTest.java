package io.springperf.web.support.servlet.filter;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.filter.RuntimeMappingWebFilter;
import io.springperf.web.core.filter.WebFilterRegistration;
import io.springperf.web.core.filter.WebFilterRegistry;
import io.springperf.web.support.servlet.context.PerfServletContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupportWebFilterRegistryTest {

    @Test
    void constructor_registersServletFilterAutoRegistration() throws Exception {
        SupportWebFilterRegistry registry = new SupportWebFilterRegistry(mock(DispatcherHandler.class));

        java.lang.reflect.Field field = io.springperf.web.context.WebComponentContainer.class
                .getDeclaredField("autoRegisterComponentMap");
        field.setAccessible(true);
        java.util.Map<?, ?> factories = (java.util.Map<?, ?>) field.get(registry);

        assertTrue(factories.containsKey(jakarta.servlet.Filter.class),
                "构造器必须注册 Filter → WebFilterRegistration 的自动包装工厂");
    }

    @Test
    void constructor_extendsWebFilterRegistry() {
        assertInstanceOf(WebFilterRegistry.class, new SupportWebFilterRegistry(mock(DispatcherHandler.class)));
    }

    @Test
    void initWithWebContext_scansServletFilterBeansAndWrapsThem() throws Exception {
        // 核心逻辑：autoRegisterWebComponent(jakarta.servlet.Filter.class) 在 initWithWebContext 时
        // 扫描 Spring 容器中的 jakarta.servlet.Filter Bean，并包装为 FilterWrapper 后注册为 WebFilterRegistration
        WebContext webContext = mock(WebContext.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(webContext.getCtx()).thenReturn(ctx);
        // 注意 stub 顺序：any(Class) 兜底在前，精确类 stub 在后（Mockito 取最后匹配的 stub）
        when(ctx.getBeansOfType(any(Class.class))).thenReturn(Collections.emptyMap());
        jakarta.servlet.Filter servletFilter = mock(jakarta.servlet.Filter.class);
        Map<String, jakarta.servlet.Filter> filterMap = new HashMap<>();
        filterMap.put("servletFilter", servletFilter);
        when(ctx.getBeansOfType(jakarta.servlet.Filter.class)).thenReturn(filterMap);
        // FilterWrapper.initWithWebContext 需要 PerfServletContext 构建 FilterConfig
        when(webContext.getWebComponent(any(Class.class))).thenReturn(mock(PerfServletContext.class));

        SupportWebFilterRegistry registry = new SupportWebFilterRegistry(mock(DispatcherHandler.class));
        registry.initWithWebContext(webContext);

        // 容器中应存在包装了 servlet Filter 的 WebFilterRegistration，其内部 filter 为 FilterWrapper
        List<WebFilterRegistration> registrations = registry.getWebComponents(WebFilterRegistration.class);
        assertEquals(1, registrations.size(), "应恰好注册一个 WebFilter");
        FilterWrapper wrapper = assertInstanceOf(FilterWrapper.class, getFilterField(registrations.get(0)),
                "注册的 filter 应为 FilterWrapper");
        assertSame(servletFilter, org.springframework.test.util.ReflectionTestUtils.getField(wrapper, "filter"),
                "FilterWrapper 必须包装扫描到的那个 servlet Filter Bean");
    }

    private static Object getFilterField(WebFilterRegistration registration) throws Exception {
        java.lang.reflect.Field field = WebFilterRegistration.class.getDeclaredField("filter");
        field.setAccessible(true);
        return field.get(registration);
    }

    @Test
    void initWithWebContext_respectsWebFilterUrlPatterns() throws Exception {
        // 复现修复前 @WebFilter(urlPatterns=...) 被完全忽略、Filter 全局生效的问题
        WebContext webContext = mock(WebContext.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(webContext.getCtx()).thenReturn(ctx);
        when(ctx.getBeansOfType(any(Class.class))).thenReturn(Collections.emptyMap());
        Map<String, jakarta.servlet.Filter> filterMap = new HashMap<>();
        filterMap.put("adminFilter", new AdminOnlyFilter());
        when(ctx.getBeansOfType(jakarta.servlet.Filter.class)).thenReturn(filterMap);
        when(webContext.getWebComponent(any(Class.class))).thenReturn(mock(PerfServletContext.class));

        SupportWebFilterRegistry registry = new SupportWebFilterRegistry(mock(DispatcherHandler.class));
        registry.initWithWebContext(webContext);

        List<WebFilterRegistration> registrations = registry.getWebComponents(WebFilterRegistration.class);
        assertEquals(1, registrations.size(), "应恰好注册一个 WebFilter");
        assertEquals(java.util.List.of("/admin/*"), readPatterns(registrations.get(0)),
                "@WebFilter urlPatterns 必须原样映射，不得混入全局 pattern");
    }

    @Test
    void initWithWebContext_filterWithoutUrlPatterns_isGlobal() throws Exception {
        WebContext webContext = mock(WebContext.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(webContext.getCtx()).thenReturn(ctx);
        when(ctx.getBeansOfType(any(Class.class))).thenReturn(Collections.emptyMap());
        Map<String, jakarta.servlet.Filter> filterMap = new HashMap<>();
        filterMap.put("globalFilter", mock(jakarta.servlet.Filter.class));
        when(ctx.getBeansOfType(jakarta.servlet.Filter.class)).thenReturn(filterMap);
        when(webContext.getWebComponent(any(Class.class))).thenReturn(mock(PerfServletContext.class));

        SupportWebFilterRegistry registry = new SupportWebFilterRegistry(mock(DispatcherHandler.class));
        registry.initWithWebContext(webContext);

        List<WebFilterRegistration> registrations = registry.getWebComponents(WebFilterRegistration.class);
        assertEquals(1, registrations.size());
        assertTrue(readPatterns(registrations.get(0)).isEmpty(), "无 urlPatterns 的 Filter 应全局生效（include 路径为空）");
    }

    @jakarta.servlet.annotation.WebFilter(urlPatterns = "/admin/*")
    static class AdminOnlyFilter implements jakarta.servlet.Filter {
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response,
                jakarta.servlet.FilterChain chain) {
        }

        @Override
        public void init(jakarta.servlet.FilterConfig filterConfig) {
        }

        @Override
        public void destroy() {
        }
    }

    private static java.util.List<String> readPatterns(WebFilterRegistration registration) throws Exception {
        java.lang.reflect.Field field = WebFilterRegistration.class.getDeclaredField("includePatterns");
        field.setAccessible(true);
        return (java.util.List<String>) field.get(registration);
    }

    @Test
    void resolveUrlPatterns_subclassFilterReadsInheritedAnnotation() {
        // 复现 M6：被子类继承（非 @Inherited）或被 CGLIB 代理的 Filter，getClass().getAnnotation
        // 取不到注解会被静默升格为全局 Filter；findMergedAnnotation 沿超类查找可正确解析。
        String[] patterns = SupportWebFilterRegistry.resolveUrlPatterns(new AdminOnlyFilterSub());
        assertEquals(1, patterns.length, "@WebFilter 注解应沿超类被解析");
        assertEquals("/admin/*", patterns[0]);
    }

    @Test
    void resolveUrlPatterns_valueOnlyAnnotation_usesValueFallback() {
        // resolveUrlPatterns 有 urlPatterns.length == 0 → value() 的回退分支，删掉会把
        // @WebFilter("/x") 声明的过滤器静默升格为全局，正是本文件其它用例在防的安全回归。
        assertArrayEquals(new String[] { "/value-only" },
                SupportWebFilterRegistry.resolveUrlPatterns(new ValueOnlyFilter()));
    }

    static class AdminOnlyFilterSub extends AdminOnlyFilter {
    }

    @jakarta.servlet.annotation.WebFilter("/value-only")
    static class ValueOnlyFilter implements jakarta.servlet.Filter {
        @Override
        public void init(jakarta.servlet.FilterConfig filterConfig) {
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response,
                jakarta.servlet.FilterChain chain) {
        }

        @Override
        public void destroy() {
        }
    }

    // ==================== 动态注册（addFilter）认领 ====================

    @Test
    void initComponentPhase3_appendsDynamicallyRegisteredFilterWithInitParams() throws Exception {
        WebContext webContext = mock(WebContext.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(webContext.getCtx()).thenReturn(ctx);
        when(ctx.getBeansOfType(any(Class.class))).thenReturn(Collections.emptyMap());
        io.springperf.web.context.ApplicationProperties props = mock(
                io.springperf.web.context.ApplicationProperties.class);
        when(props.getPropertyNames(org.mockito.ArgumentMatchers.anyString())).thenReturn(Collections.emptyList());
        when(webContext.getProps()).thenReturn(props);
        PerfServletContext servletContext = new PerfServletContext(webContext);
        when(webContext.getWebComponent(any(Class.class))).thenReturn(servletContext);
        try {
            TrackingFilter filter = new TrackingFilter();
            jakarta.servlet.FilterRegistration.Dynamic dynamic = servletContext.addFilter("dyn", filter);
            dynamic.setInitParameter("k", "v");
            dynamic.addMappingForUrlPatterns(null, true, "/dyn/*");

            SupportWebFilterRegistry registry = new SupportWebFilterRegistry(mock(DispatcherHandler.class));
            registry.initWithWebContext(webContext);
            registry.initComponentPhase1();
            registry.initComponentPhase2();
            registry.initComponentPhase3();

            assertEquals(1, filter.initCalls, "动态注册的 filter 必须走 init（否则 init-param 永远不生效）");
            assertEquals("v", filter.initParam);
            assertEquals("dyn", filter.filterName, "FilterConfig#getFilterName 必须返回注册名，不能带内部前缀");
            List<?> allFilters = (List<?>) org.springframework.test.util.ReflectionTestUtils.getField(registry,
                    "allFilters");
            assertNotNull(allFilters);
            assertEquals(1, allFilters.size(), "动态注册的 filter 应进入 filter 链");
            RuntimeMappingWebFilter runtime = assertInstanceOf(RuntimeMappingWebFilter.class, allFilters.get(0),
                    "有路径规则时应包装为运行时匹配");
            assertTrue(runtime.matches("/dyn/page"), "动态注册的 filter 必须对匹配路径生效");
            assertFalse(runtime.matches("/other"), "不得静默升格为全局 filter");
            assertInstanceOf(FilterWrapper.class, runtime.getDelegate(), "链上的 delegate 应是 FilterWrapper");

            // 注册进链还不够：必须在一次匹配路径的请求中真的被调用
            io.springperf.web.http.WebServerHttpRequest httpRequest = mock(
                    io.springperf.web.http.WebServerHttpRequest.class);
            io.springperf.web.http.WebServerHttpResponse httpResponse = mock(
                    io.springperf.web.http.WebServerHttpResponse.class);
            io.springperf.web.core.filter.FilterChain filterChain = mock(
                    io.springperf.web.core.filter.FilterChain.class);
            when(httpRequest.getPath()).thenReturn("/dyn/page");
            when(httpRequest.getRequestContext()).thenReturn(mock(io.springperf.web.http.RequestContext.class));
            runtime.doFilter(httpRequest, httpResponse, filterChain);
            assertEquals(1, filter.doFilterCalls, "动态注册的 filter 必须在匹配路径的请求中被调用一次");
        } finally {
            servletContext.destroyComponent();
        }
    }

    /** 记录 init / doFilter 次数、init-param 与 filter name：动态注册的配置与执行都必须真的生效。 */
    static class TrackingFilter implements jakarta.servlet.Filter {
        int initCalls;
        int doFilterCalls;
        String initParam;
        String filterName;

        @Override
        public void init(jakarta.servlet.FilterConfig filterConfig) {
            initCalls++;
            initParam = filterConfig.getInitParameter("k");
            filterName = filterConfig.getFilterName();
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response,
                jakarta.servlet.FilterChain chain) {
            doFilterCalls++;
        }

        @Override
        public void destroy() {
        }
    }
}
