package io.springperf.web.filter;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.BaseWebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultFilterChainTest {

    @Test
    void doFilter_noFilters_callsDispatcher() throws Exception {
        DispatcherHandler dispatcher = mock(DispatcherHandler.class);
        DefaultFilterChain chain = new DefaultFilterChain(Collections.emptyList(), dispatcher);

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        when(request.getFilterIndexAndIncrement()).thenReturn(0);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        chain.doFilter(request, response);

        verify(dispatcher).handle(request, response);
    }

    @Test
    void doFilter_oneFilter_callsFilterThenDispatcher() throws Exception {
        DispatcherHandler dispatcher = mock(DispatcherHandler.class);
        WebFilter filter = mock(WebFilter.class);
        DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(filter), dispatcher);

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        when(request.getFilterIndexAndIncrement()).thenReturn(0, 1);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        chain.doFilter(request, response);

        verify(filter).doFilter(request, response, chain);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void doFilter_twoFilters_callsBothInOrder() throws Exception {
        DispatcherHandler dispatcher = mock(DispatcherHandler.class);
        WebFilter filter1 = mock(WebFilter.class);
        WebFilter filter2 = mock(WebFilter.class);
        DefaultFilterChain chain = new DefaultFilterChain(Arrays.asList(filter1, filter2), dispatcher);

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

        // When filter2 calls chain.doFilter again, it invokes dispatcher
        when(request.getFilterIndexAndIncrement()).thenReturn(2);
        chain.doFilter(request, response);
        verify(dispatcher).handle(request, response);
    }

    @Test
    void doFilter_allFiltersConsumed_callsDispatcher() throws Exception {
        DispatcherHandler dispatcher = mock(DispatcherHandler.class);
        DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(mock(WebFilter.class)), dispatcher);

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        // index 0 → filter called, index 1 → out of bounds → dispatcher
        when(request.getFilterIndexAndIncrement()).thenReturn(0, 1);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        chain.doFilter(request, response);
        // After filter is consumed

        when(request.getFilterIndexAndIncrement()).thenReturn(1);
        chain.doFilter(request, response);
        verify(dispatcher).handle(request, response);
    }

    @Test
    void doFilter_withNullDispatcher_throwsNPE() {
        DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(mock(WebFilter.class)), null);

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        when(request.getFilterIndexAndIncrement()).thenReturn(1); // skip the filter
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        assertThrows(NullPointerException.class, () -> chain.doFilter(request, response));
    }
}