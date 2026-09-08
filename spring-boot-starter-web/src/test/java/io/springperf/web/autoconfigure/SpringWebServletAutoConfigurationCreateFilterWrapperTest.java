package io.springperf.web.autoconfigure;

import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.core.filter.WebFilterRegistration;
import io.springperf.web.support.servlet.filter.FilterWrapper;
import javax.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpringWebServletAutoConfigurationCreateFilterWrapperTest {

    private SpringWebServletAutoConfiguration config;
    private ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        config = new SpringWebServletAutoConfiguration();
        applicationContext = mock(ApplicationContext.class);
        config.setApplicationContext(applicationContext);
    }

    private WebFilterRegistration invokeCreateFilterWrapper(AbstractFilterRegistrationBean<?> reg) {
        try {
            Method m = SpringWebServletAutoConfiguration.class
                    .getDeclaredMethod("createFilterWrapper", AbstractFilterRegistrationBean.class);
            m.setAccessible(true);
            return (WebFilterRegistration) m.invoke(config, reg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private WebFilter wrappedFilter(WebFilterRegistration registration) throws Exception {
        Field f = WebFilterRegistration.class.getDeclaredField("filter");
        f.setAccessible(true);
        return (WebFilter) f.get(registration);
    }

    @Test
    void createFilterWrapper_normalFilter_buildsRegistration() throws Exception {
        Filter filter = mock(Filter.class);
        @SuppressWarnings("unchecked")
        AbstractFilterRegistrationBean<Filter> registration = mock(AbstractFilterRegistrationBean.class);
        when(registration.getFilter()).thenReturn(filter);
        Set<String> urlPatterns = new LinkedHashSet<>();
        urlPatterns.add("/api/*");
        when(registration.getUrlPatterns()).thenReturn(urlPatterns);

        WebFilterRegistration result = invokeCreateFilterWrapper(registration);

        assertNotNull(result);
        assertTrue(wrappedFilter(result) instanceof FilterWrapper, "应包装为 FilterWrapper");
        assertTrue(result.matches("/api/users"), "路径模式应生效");
        assertFalse(result.matches("/other"), "非匹配路径应 false");
    }

    @Test
    void createFilterWrapper_getFilterFails_plainBean_returnsNull() {
        @SuppressWarnings("unchecked")
        AbstractFilterRegistrationBean<Filter> registration = mock(AbstractFilterRegistrationBean.class);
        when(registration.getFilter()).thenThrow(new IllegalArgumentException("no ctx"));

        assertNull(invokeCreateFilterWrapper(registration));
    }

    @Test
    void createFilterWrapper_delegatingFilterProxyFallback_resolvesTargetBean() throws Exception {
        // 真实匿名子类：覆盖 protected getTargetBeanName() 返回固定名；getFilter() 抛异常模拟 WebApplicationContext 缺失
        DelegatingFilterProxyRegistrationBean registration =
                new DelegatingFilterProxyRegistrationBean("targetBean") {
                    @Override
                    protected String getTargetBeanName() {
                        return "targetBean";
                    }

                    @Override
                    public org.springframework.web.filter.DelegatingFilterProxy getFilter() {
                        throw new IllegalStateException("no ctx");
                    }
                };

        Filter target = mock(Filter.class);
        when(applicationContext.getBean("targetBean", Filter.class)).thenReturn(target);

        WebFilterRegistration result = invokeCreateFilterWrapper(registration);

        assertNotNull(result, "DelegatingFilterProxy 应解析目标 bean 并包装");
        assertTrue(wrappedFilter(result) instanceof FilterWrapper);
    }

    @Test
    void createFilterWrapper_delegatingFilterProxyNoTarget_returnsNull() {
        // getTargetBeanName() 返回 null → 告警并跳过
        DelegatingFilterProxyRegistrationBean registration =
                new DelegatingFilterProxyRegistrationBean("unused") {
                    @Override
                    protected String getTargetBeanName() {
                        return null;
                    }

                    @Override
                    public org.springframework.web.filter.DelegatingFilterProxy getFilter() {
                        throw new IllegalStateException("no ctx");
                    }
                };
        assertNull(invokeCreateFilterWrapper(registration), "无 targetBeanName 应返回 null");
    }
}
