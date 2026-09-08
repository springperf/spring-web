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
        // 鏍稿績閫昏緫锛歛utoRegisterWebComponent(javax.servlet.Filter.class) 鍦?initWithWebContext 鏃?
        // 鎵弿 Spring 瀹瑰櫒涓殑 javax.servlet.Filter Bean锛屽苟鍖呰涓?FilterWrapper 鍚庢敞鍐屼负 WebFilterRegistration
        WebContext webContext = mock(WebContext.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(webContext.getCtx()).thenReturn(ctx);
        // 娉ㄦ剰 stub 椤哄簭锛歛ny(Class) 鍏滃簳鍦ㄥ墠锛岀簿纭被 stub 鍦ㄥ悗锛圡ockito 鍙栨渶鍚庡尮閰嶇殑 stub锛?
        when(ctx.getBeansOfType(any(Class.class))).thenReturn(Collections.emptyMap());
        javax.servlet.Filter servletFilter = mock(javax.servlet.Filter.class);
        Map<String, javax.servlet.Filter> filterMap = new HashMap<>();
        filterMap.put("servletFilter", servletFilter);
        when(ctx.getBeansOfType(javax.servlet.Filter.class)).thenReturn(filterMap);
        // FilterWrapper.initWithWebContext 闇€瑕?PerfServletContext 鏋勫缓 FilterConfig
        when(webContext.getWebComponent(any(Class.class))).thenReturn(mock(PerfServletContext.class));

        SupportWebFilterRegistry registry = new SupportWebFilterRegistry(mock(DispatcherHandler.class));
        registry.initWithWebContext(webContext);

        // 瀹瑰櫒涓簲瀛樺湪鍖呰浜?servlet Filter 鐨?WebFilterRegistration锛屽叾鍐呴儴 filter 涓?FilterWrapper
        List<WebFilterRegistration> registrations = registry.getWebComponents(WebFilterRegistration.class);
        assertTrue(!registrations.isEmpty(), "javax.servlet.Filter Bean 搴旇鎵弿骞舵敞鍐屼负 WebFilterRegistration");
        Object wrapped = getFilterField(registrations.get(0));
        assertInstanceOf(FilterWrapper.class, wrapped, "娉ㄥ唽鐨?filter 搴斾负 FilterWrapper");
        assertNotNull(wrapped, "鍖呰鍚庣殑 FilterWrapper 涓嶅緱涓?null");
    }

    private static Object getFilterField(WebFilterRegistration registration) throws Exception {
        java.lang.reflect.Field field = WebFilterRegistration.class.getDeclaredField("filter");
        field.setAccessible(true);
        return field.get(registration);
    }
}