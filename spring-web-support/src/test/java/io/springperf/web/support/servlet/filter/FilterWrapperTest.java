package io.springperf.web.support.servlet.filter;

import io.springperf.web.filter.FilterChain;
import io.springperf.web.filter.WebFilter;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.Filter;

import static org.junit.jupiter.api.Assertions.*;
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

    // ---- Constructor variants ----

    @Test
    void constructor_filterOnly_defaultOrder() {
        FilterWrapper wrapper = new FilterWrapper(servletFilter);

        assertEquals(WebFilter.defaultOrder, wrapper.getOrder());
    }

    @Test
    void constructor_withPathRules_defaultOrder() {
        FilterWrapper wrapper = new FilterWrapper(servletFilter, new String[]{"/api/*"});

        assertEquals(WebFilter.defaultOrder, wrapper.getOrder());
    }

    @Test
    void constructor_withPathRulesAndOrder() {
        FilterWrapper wrapper = new FilterWrapper(servletFilter, new String[]{"/api/*"}, 10);

        assertEquals(10, wrapper.getOrder());
    }

    // ---- Path matching: no rules = match all ----

    @Test
    void doFilter_noPathRules_callsDoFilterInternal() throws Exception {
        FilterWrapper wrapper = new FilterWrapper(servletFilter);
        when(request.getPath()).thenReturn("/any/path");
        when(request.getRequestContext()).thenReturn(requestContext);

        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(any(), any(), any());
    }

    // ---- Path matching with rules ----

    @Test
    void doFilter_matchingPath_callsDoFilterInternal() throws Exception {
        FilterWrapper wrapper = new FilterWrapper(servletFilter, new String[]{"/api/*"});
        when(request.getPath()).thenReturn("/api/users");
        when(request.getRequestContext()).thenReturn(requestContext);

        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(any(), any(), any());
    }

    @Test
    void doFilter_nonMatchingPath_skipsDoFilterInternal() throws Exception {
        FilterWrapper wrapper = new FilterWrapper(servletFilter, new String[]{"/api/*"});
        when(request.getPath()).thenReturn("/other");

        wrapper.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(servletFilter);
    }

    @Test
    void doFilter_multipleRules_matchesSecondRule() throws Exception {
        FilterWrapper wrapper = new FilterWrapper(servletFilter, new String[]{"/api/*", "/admin/*"});
        when(request.getPath()).thenReturn("/admin/settings");
        when(request.getRequestContext()).thenReturn(requestContext);

        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(any(), any(), any());
    }

    @Test
    void doFilter_exactPathRule_matches() throws Exception {
        FilterWrapper wrapper = new FilterWrapper(servletFilter, new String[]{"/login"});
        when(request.getPath()).thenReturn("/login");
        when(request.getRequestContext()).thenReturn(requestContext);

        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(any(), any(), any());
    }

    @Test
    void doFilter_suffixRule_matches() throws Exception {
        FilterWrapper wrapper = new FilterWrapper(servletFilter, new String[]{"*.do"});
        when(request.getPath()).thenReturn("/test.do");
        when(request.getRequestContext()).thenReturn(requestContext);

        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(any(), any(), any());
    }

    // ---- ServletAdapterContext lifecycle ----

    @Test
    void doFilterInternal_createsServletAdapterContextLazily() throws Exception {
        when(request.getPath()).thenReturn("/any");
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(ServletAdapterContext.REQUEST_ATTRIBUTE_NAME)).thenReturn(null);

        FilterWrapper wrapper = spy(new FilterWrapper(servletFilter));

        wrapper.doFilter(request, response, chain);

        verify(requestContext).setAttribute(eq(ServletAdapterContext.REQUEST_ATTRIBUTE_NAME), any());
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

        when(request.getPath()).thenReturn("/any");
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(ServletAdapterContext.REQUEST_ATTRIBUTE_NAME)).thenReturn(existingCtx);

        FilterWrapper wrapper = spy(new FilterWrapper(servletFilter));

        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(restRequest, restResponse, servletFilterChain);
    }

    // ---- getComponentName ----

    @Test
    void getComponentName_returnsFilterClassName() {
        Filter servletFilter = new TestFilter();
        FilterWrapper wrapper = new FilterWrapper(servletFilter);

        assertEquals("io.springperf.web.support.servlet.filter.FilterWrapperTest$TestFilter",
                wrapper.getComponentName());
    }

    @Test
    void toString_withoutPathRules_returnsFilterToString() {
        when(servletFilter.toString()).thenReturn("MyFilter@123");

        FilterWrapper wrapper = new FilterWrapper(servletFilter);

        assertEquals("MyFilter@123", wrapper.toString());
    }

    @Test
    void toString_withPathRules_includesFilterToString() {
        when(servletFilter.toString()).thenReturn("MyFilter@123");

        FilterWrapper wrapper = new FilterWrapper(servletFilter, new String[]{"/api/*"});

        String str = wrapper.toString();
        assertTrue(str.contains("MyFilter@123"));
        assertTrue(str.contains("PrefixMatch"));
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

    // ---- Helper ----

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