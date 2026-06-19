package io.springperf.web.support.servlet.filter;

import io.springperf.web.filter.FilterChain;
import io.springperf.web.filter.WebFilter;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatchFilterWrapperTest {

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
    void constructor_withPathRules_defaultOrder() {
        MatchFilterWrapper wrapper = new MatchFilterWrapper(servletFilter, new String[]{"/api/*"});

        assertEquals(WebFilter.defaultOrder, wrapper.getOrder());
    }

    @Test
    void constructor_withPathRulesAndOrder() {
        MatchFilterWrapper wrapper = new MatchFilterWrapper(servletFilter, new String[]{"/api/*"}, 10);

        assertEquals(10, wrapper.getOrder());
    }

    @Test
    void doFilter_matchingPath_callsDoFilterInternal() throws Exception {
        when(request.getPath()).thenReturn("/api/users");
        when(request.getRequestContext()).thenReturn(requestContext);

        MatchFilterWrapper wrapper = new MatchFilterWrapper(servletFilter, new String[]{"/api/*"});
        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(any(), any(), any());
    }

    @Test
    void doFilter_nonMatchingPath_skipsDoFilterInternal() throws Exception {
        when(request.getPath()).thenReturn("/other");

        MatchFilterWrapper wrapper = new MatchFilterWrapper(servletFilter, new String[]{"/api/*"});
        wrapper.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(servletFilter);
    }

    @Test
    void doFilter_multipleRules_matchesSecondRule() throws Exception {
        when(request.getPath()).thenReturn("/admin/settings");
        when(request.getRequestContext()).thenReturn(requestContext);

        MatchFilterWrapper wrapper = new MatchFilterWrapper(servletFilter, new String[]{"/api/*", "/admin/*"});
        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(any(), any(), any());
    }

    @Test
    void doFilter_exactPathRule_matches() throws Exception {
        when(request.getPath()).thenReturn("/login");
        when(request.getRequestContext()).thenReturn(requestContext);

        MatchFilterWrapper wrapper = new MatchFilterWrapper(servletFilter, new String[]{"/login"});
        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(any(), any(), any());
    }

    @Test
    void doFilter_suffixRule_matches() throws Exception {
        when(request.getPath()).thenReturn("/test.do");
        when(request.getRequestContext()).thenReturn(requestContext);

        MatchFilterWrapper wrapper = new MatchFilterWrapper(servletFilter, new String[]{"*.do"});
        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(any(), any(), any());
    }

    @Test
    void doFilterInternal_createsServletAdapterContextLazily() throws Exception {
        when(request.getPath()).thenReturn("/api/test");
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(ServletAttribute.getAttributeKey())).thenReturn(null);

        MatchFilterWrapper wrapper = spy(new MatchFilterWrapper(servletFilter, new String[]{"/api/*"}));
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

        when(request.getPath()).thenReturn("/api/test");
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(ServletAttribute.getAttributeKey())).thenReturn(existingCtx);

        MatchFilterWrapper wrapper = spy(new MatchFilterWrapper(servletFilter, new String[]{"/api/*"}));
        wrapper.doFilter(request, response, chain);

        verify(servletFilter).doFilter(restRequest, restResponse, servletFilterChain);
    }

    @Test
    void toString_withPathRules_includesFilterToString() {
        when(servletFilter.toString()).thenReturn("MyFilter@123");

        MatchFilterWrapper wrapper = new MatchFilterWrapper(servletFilter, new String[]{"/api/*"});

        String str = wrapper.toString();
        assertTrue(str.contains("MyFilter@123"));
        assertTrue(str.contains("PrefixMatch"));
    }

    @Test
    void toString_withoutPathRules_returnsFilterToString() {
        when(servletFilter.toString()).thenReturn("MyFilter@123");

        MatchFilterWrapper wrapper = new MatchFilterWrapper(servletFilter, new String[0]);

        assertEquals("MyFilter@123", wrapper.toString());
    }

    @Test
    void createServletAdapterContext_createsValidContext() {
        MatchFilterWrapper wrapper = new MatchFilterWrapper(servletFilter, new String[]{"/api/*"});

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