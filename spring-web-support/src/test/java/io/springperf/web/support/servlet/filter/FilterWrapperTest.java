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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.Filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        verify(servletFilter).doFilter(any(javax.servlet.ServletRequest.class), any(javax.servlet.ServletResponse.class), any(javax.servlet.FilterChain.class));
    }

    @Test
    void doFilterInternal_reusesExistingServletAdapterContext() throws Exception {
        PerfHttpServletRequest restRequest = mock(PerfHttpServletRequest.class);
        PerfHttpServletResponse restResponse = mock(PerfHttpServletResponse.class);
        javax.servlet.FilterChain servletFilterChain = mock(javax.servlet.FilterChain.class);

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
    void getComponentName_returnsFilterClassName() {
        Filter servletFilter = new TestFilter();
        FilterWrapper wrapper = new FilterWrapper(servletFilter);

        assertEquals("io.springperf.web.support.servlet.filter.FilterWrapperTest$TestFilter",
                wrapper.getComponentName());
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
        public void doFilter(javax.servlet.ServletRequest request, javax.servlet.ServletResponse response, javax.servlet.FilterChain chain) {
        }

        @Override
        public void init(javax.servlet.FilterConfig filterConfig) {
        }

        @Override
        public void destroy() {
        }
    }
}