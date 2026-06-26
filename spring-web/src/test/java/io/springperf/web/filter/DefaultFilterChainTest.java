package io.springperf.web.filter;

import io.springperf.web.http.BaseWebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DefaultFilterChainTest {

    @Test
    void doFilter_noFilters_callsTerminal() throws Exception {
        WebFilterRegistry.FilterChainTerminal terminal = mock(WebFilterRegistry.FilterChainTerminal.class);
        DefaultFilterChain chain = new DefaultFilterChain(Collections.emptyList(), null, terminal);

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        when(request.getFilterIndexAndIncrement()).thenReturn(0);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        chain.doFilter(request, response);

        verify(terminal).doFilter(request, response, null);
    }

    @Test
    void doFilter_oneFilter_callsFilterThenTerminal() throws Exception {
        WebFilterRegistry.FilterChainTerminal terminal = mock(WebFilterRegistry.FilterChainTerminal.class);
        WebFilter filter = mock(WebFilter.class);
        DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(filter), null, terminal);

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        when(request.getFilterIndexAndIncrement()).thenReturn(0, 1);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        chain.doFilter(request, response);

        verify(filter).doFilter(request, response, chain);
        verifyNoInteractions(terminal);
    }

    @Test
    void doFilter_twoFilters_callsBothInOrder() throws Exception {
        WebFilterRegistry.FilterChainTerminal terminal = mock(WebFilterRegistry.FilterChainTerminal.class);
        WebFilter filter1 = mock(WebFilter.class);
        WebFilter filter2 = mock(WebFilter.class);
        DefaultFilterChain chain = new DefaultFilterChain(Arrays.asList(filter1, filter2), null, terminal);

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        when(request.getFilterIndexAndIncrement()).thenReturn(0, 1, 2);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        // First call invokes filter1
        chain.doFilter(request, response);
        verify(filter1).doFilter(request, response, chain);

        // When filter1 calls chain.doFilter again, it invokes filter2
        when(request.getFilterIndexAndIncrement()).thenReturn(1, 2);
        chain.doFilter(request, response);
        verify(filter2).doFilter(request, response, chain);

        // When filter2 calls chain.doFilter again, it invokes terminal
        when(request.getFilterIndexAndIncrement()).thenReturn(2);
        chain.doFilter(request, response);
        verify(terminal).doFilter(request, response, null);
    }

    @Test
    void doFilter_allFiltersConsumed_callsTerminal() throws Exception {
        WebFilterRegistry.FilterChainTerminal terminal = mock(WebFilterRegistry.FilterChainTerminal.class);
        DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(mock(WebFilter.class)), null, terminal);

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        // index 0 → filter called, index 1 → out of bounds → terminal
        when(request.getFilterIndexAndIncrement()).thenReturn(0, 1);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        chain.doFilter(request, response);
        // After filter is consumed

        when(request.getFilterIndexAndIncrement()).thenReturn(1);
        chain.doFilter(request, response);
        verify(terminal).doFilter(request, response, null);
    }

    @Test
    void doFilter_withNullTerminal_throwsNPE() {
        DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(mock(WebFilter.class)), null, null);

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        when(request.getFilterIndexAndIncrement()).thenReturn(1); // skip the filter
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        assertThrows(NullPointerException.class, () -> chain.doFilter(request, response));
    }
}