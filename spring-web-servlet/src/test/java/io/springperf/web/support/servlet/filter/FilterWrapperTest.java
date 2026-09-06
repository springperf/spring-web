package io.springperf.web.support.servlet.filter;

import io.springperf.web.core.filter.FilterChain;
import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FilterWrapperTest {

    @Mock
    Filter servletFilter;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    FilterChain chain;

    @Mock
    RequestContext requestContext;

    @Test
    void constructor_filterOnly_defaultOrder() {
        FilterWrapper wrapper = new FilterWrapper(servletFilter);

        assertEquals(WebFilter.defaultOrder, wrapper.getOrder());
    }

    @Test
    void doFilter_callsDoFilterInternal() throws Exception {
        when(request.getRequestContext()).thenReturn(requestContext);

        FilterWrapper wrapper = new FilterWrapper(servletFilter);
        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(any(), any(), any());
    }

    @Test
    void doFilterInternal_createsServletAdapterContextLazily() throws Exception {
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(ServletAttribute.getAttributeKey())).thenReturn(null);

        FilterWrapper wrapper = spy(new FilterWrapper(servletFilter));
        wrapper.doFilter(request, response, chain);

        verify(requestContext).setAttribute(eq(ServletAttribute.getAttributeKey()), any());
        verify(servletFilter).doFilter(any(jakarta.servlet.ServletRequest.class), any(jakarta.servlet.ServletResponse.class), any(jakarta.servlet.FilterChain.class));
    }

    @Test
    void doFilterInternal_reusesExistingServletAdapterContext() throws Exception {
        PerfHttpServletRequest restRequest = mock(PerfHttpServletRequest.class);
        PerfHttpServletResponse restResponse = mock(PerfHttpServletResponse.class);
        jakarta.servlet.FilterChain servletFilterChain = mock(jakarta.servlet.FilterChain.class);

        ServletAdapterContext existingCtx = mock(ServletAdapterContext.class);
        when(existingCtx.getRequest()).thenReturn(restRequest);
        when(existingCtx.getResponse()).thenReturn(restResponse);
        when(existingCtx.getFilterChain()).thenReturn(servletFilterChain);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(ServletAttribute.getAttributeKey())).thenReturn(existingCtx);

        FilterWrapper wrapper = spy(new FilterWrapper(servletFilter));
        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(restRequest, restResponse, servletFilterChain);
    }

    @Test
    void getComponentName_containsClassNameAndInstanceIdentity() {
        // 回归 P2 正确性组 C4：同类不同实例不得被按类名去重误杀；
        // 同一实例重复包装时 identityHashCode 相同，名字相同（仍去重）。
        Filter servletFilter = new TestFilter();
        FilterWrapper wrapper = new FilterWrapper(servletFilter);

        String name = wrapper.getComponentName();
        assertTrue(name.startsWith("io.springperf.web.support.servlet.filter.FilterWrapperTest$TestFilter@"));
        assertEquals(name, new FilterWrapper(servletFilter).getComponentName());
    }

    @Test
    void getComponentName_differentInstances_distinct() {
        FilterWrapper w1 = new FilterWrapper(new TestFilter());
        FilterWrapper w2 = new FilterWrapper(new TestFilter());

        assertNotEquals(w1.getComponentName(), w2.getComponentName(),
                "不同 filter 实例必须持有不同 componentName，否则低 order 者被静默销毁");
    }

    @Test
    void toString_returnsFilterToString() {
        when(servletFilter.toString()).thenReturn("MyFilter@123");

        FilterWrapper wrapper = new FilterWrapper(servletFilter);

        assertEquals("MyFilter@123", wrapper.toString());
    }

    @Test
    void createServletAdapterContext_createsValidContext() {
        FilterWrapper wrapper = new FilterWrapper(servletFilter);

        ServletAdapterContext ctx = wrapper.createServletAdapterContext(request, response, chain);

        assertNotNull(ctx);
        assertNotNull(ctx.getRequest());
        assertNotNull(ctx.getResponse());
        assertNotNull(ctx.getFilterChain());
    }

    static class TestFilter implements Filter {
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response, jakarta.servlet.FilterChain chain) {
        }

        @Override
        public void init(jakarta.servlet.FilterConfig filterConfig) {
        }

        @Override
        public void destroy() {
        }
    }
}