package io.springperf.web.support.servlet.filter;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.filter.WebFilterRegistration;
import io.springperf.web.core.filter.WebFilterRegistry;
import io.springperf.web.support.servlet.context.PerfServletContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupportWebFilterRegistryTest {

    @Test
    void constructor_doesNotThrow() {
        assertDoesNotThrow(() -> new SupportWebFilterRegistry(mock(DispatcherHandler.class)));
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
        assertTrue(!registrations.isEmpty(), "jakarta.servlet.Filter Bean 应被扫描并注册为 WebFilterRegistration");
        Object wrapped = getFilterField(registrations.get(0));
        assertInstanceOf(FilterWrapper.class, wrapped, "注册的 filter 应为 FilterWrapper");
        assertNotNull(wrapped, "包装后的 FilterWrapper 不得为 null");
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
        assertTrue(readPatterns(registrations.get(0)).contains("/admin/*"),
                "@WebFilter urlPatterns 应映射到 WebFilter 的 include 路径");
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
        assertTrue(readPatterns(registrations.get(0)).isEmpty(),
                "无 urlPatterns 的 Filter 应全局生效（include 路径为空）");
    }

    @jakarta.servlet.annotation.WebFilter(urlPatterns = "/admin/*")
    static class AdminOnlyFilter implements jakarta.servlet.Filter {
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response,
                             jakarta.servlet.FilterChain chain) { }

        @Override
        public void init(jakarta.servlet.FilterConfig filterConfig) { }

        @Override
        public void destroy() { }
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

    static class AdminOnlyFilterSub extends AdminOnlyFilter {}
}