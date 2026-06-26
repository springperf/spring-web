package io.springperf.web.core.filter;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeMappingWebFilterTest {

    @Test
    void constructor_fromRegistration() {
        WebFilter filter = mock(WebFilter.class);
        WebFilterRegistration registration = new WebFilterRegistration(filter)
                .addPathPatterns("/api/*")
                .excludePathPatterns("/admin/*")
                .order(42);

        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(registration);
        assertSame(filter, runtime.getDelegate());
        assertEquals(42, runtime.getOrder());
    }

    @Test
    void matches_noPatterns_returnsTrue() {
        WebFilter filter = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(filter, new String[0], new String[0]);
        assertTrue(runtime.matches("/any/path"));
    }

    @Test
    void matches_includeMatch_returnsTrue() {
        WebFilter filter = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(filter,
                new String[]{"/api/*"}, new String[0]);
        assertTrue(runtime.matches("/api/users"));
    }

    @Test
    void matches_includeNoMatch_returnsFalse() {
        WebFilter filter = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(filter,
                new String[]{"/api/*"}, new String[0]);
        assertFalse(runtime.matches("/other"));
    }

    @Test
    void matches_excludeMatch_returnsFalse() {
        WebFilter filter = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(filter,
                new String[]{"/api/*"}, new String[]{"/api/secret"});
        assertFalse(runtime.matches("/api/secret"));
    }

    @Test
    void matches_excludeNoMatchIncludeMatch_returnsTrue() {
        WebFilter filter = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(filter,
                new String[]{"/api/*"}, new String[]{"/admin/*"});
        assertTrue(runtime.matches("/api/users"));
    }

    @Test
    void matches_onlyExcludeExcluded_returnsFalse() {
        WebFilter filter = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(filter,
                new String[0], new String[]{"/admin/*"});
        assertFalse(runtime.matches("/admin/settings"));
    }

    @Test
    void matches_onlyExcludeNotExcluded_returnsTrue() {
        WebFilter filter = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(filter,
                new String[0], new String[]{"/admin/*"});
        assertTrue(runtime.matches("/api/users"));
    }

    @Test
    void doFilter_match_delegatesToWrappedFilter() throws Exception {
        WebFilter delegate = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(delegate,
                new String[]{"/api/*"}, new String[0]);
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        when(request.getPath()).thenReturn("/api/users");
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        FilterChain chain = mock(FilterChain.class);

        runtime.doFilter(request, response, chain);
        verify(delegate).doFilter(request, response, chain);
        verifyNoInteractions(chain);
    }

    @Test
    void doFilter_noMatch_continuesChain() throws Exception {
        WebFilter delegate = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(delegate,
                new String[]{"/api/*"}, new String[0]);
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        when(request.getPath()).thenReturn("/other");
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        FilterChain chain = mock(FilterChain.class);

        runtime.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(delegate);
    }

    @Test
    void matches_prefixPatternIncludesExactEquals_returnsTrue() {
        WebFilter filter = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(filter,
                new String[]{"/api/*"}, new String[0]);
        // /api/* (servlet prefix) matches /api (prefix itself)
        assertTrue(runtime.matches("/api"));
    }

    @Test
    void matches_allPattern_returnsTrue() {
        WebFilter filter = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(filter,
                new String[]{"/*"}, new String[0]);
        assertTrue(runtime.matches("/any/path"));
    }

    @Test
    void matches_suffixPatternMatch_returnsTrue() {
        WebFilter filter = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(filter,
                new String[]{"*.json"}, new String[0]);
        assertTrue(runtime.matches("/api/data.json"));
    }

    @Test
    void matches_suffixPatternNoMatch_returnsFalse() {
        WebFilter filter = mock(WebFilter.class);
        RuntimeMappingWebFilter runtime = new RuntimeMappingWebFilter(filter,
                new String[]{"*.json"}, new String[0]);
        assertFalse(runtime.matches("/api/data.xml"));
    }
}
