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

class SpringWebSupportAutoConfigurationCreateFilterWrapperTest {

    private SpringWebSupportAutoConfiguration config;
    private ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        config = new SpringWebSupportAutoConfiguration();
        applicationContext = mock(ApplicationContext.class);
        config.setApplicationContext(applicationContext);
    }

    private WebFilterRegistration invokeCreateFilterWrapper(AbstractFilterRegistrationBean<?> reg) {
        try {
            Method m = SpringWebSupportAutoConfiguration.class
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
        assertTrue(wrappedFilter(result) instanceof FilterWrapper, "搴斿寘瑁呬负 FilterWrapper");
        assertTrue(result.matches("/api/users"), "璺緞妯″紡搴旂敓鏁?);
        assertFalse(result.matches("/other"), "闈炲尮閰嶈矾寰勫簲 false");
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
        // 鐪熷疄鍖垮悕瀛愮被锛氳鐩?protected getTargetBeanName() 杩斿洖鍥哄畾鍚嶏紱getFilter() 鎶涘紓甯告ā鎷?WebApplicationContext 缂哄け
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

        assertNotNull(result, "DelegatingFilterProxy 搴旇В鏋愮洰鏍?bean 骞跺寘瑁?);
        assertTrue(wrappedFilter(result) instanceof FilterWrapper);
    }

    @Test
    void createFilterWrapper_delegatingFilterProxyNoTarget_returnsNull() {
        // getTargetBeanName() 杩斿洖 null 鈫?鍛婅骞惰烦杩?
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
        assertNull(invokeCreateFilterWrapper(registration), "鏃?targetBeanName 搴旇繑鍥?null");
    }
}