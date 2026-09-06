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
}