package io.springperf.web.core.filter;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.http.BaseWebServerHttpRequest;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultFilterChainTest {

    @Test
    void doFilter_noFilters_callsHandleAfterFilter() throws Exception {
        DispatcherHandler dh = mock(DispatcherHandler.class);
        MappingResult mr = MappingResult.notFound();
        BaseWebServerHttpRequest request = requestWithFilterIndex(0);
        MappingResult.set(request, mr);

        DefaultFilterChain chain = new DefaultFilterChain(dh, Collections.emptyList());
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        chain.doFilter(request, response);

        verify(dh).handleAfterFilter(request, response, mr);
    }

    @Test
    void doFilter_oneFilter_callsFilterThenHandleAfterFilter() throws Exception {
        DispatcherHandler dh = mock(DispatcherHandler.class);
        WebFilter filter = mock(WebFilter.class);
        MappingResult mr = MappingResult.notFound();
        BaseWebServerHttpRequest request = requestWithFilterIndex(0, 1);
        MappingResult.set(request, mr);

        DefaultFilterChain chain = new DefaultFilterChain(dh, Collections.singletonList(filter));
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        chain.doFilter(request, response);

        verify(filter).doFilter(request, response, chain);
        verify(dh, never()).handleAfterFilter(any(), any(), any());
    }

    @Test
    void doFilter_twoFilters_callsBothInOrder() throws Exception {
        DispatcherHandler dh = mock(DispatcherHandler.class);
        WebFilter filter1 = mock(WebFilter.class);
        WebFilter filter2 = mock(WebFilter.class);
        MappingResult mr = MappingResult.notFound();
        BaseWebServerHttpRequest request = requestWithFilterIndex(0, 1, 2);
        MappingResult.set(request, mr);

        DefaultFilterChain chain = new DefaultFilterChain(dh, Arrays.asList(filter1, filter2));
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        // First call invokes filter1
        chain.doFilter(request, response);
        verify(filter1).doFilter(request, response, chain);

        // When filter1 calls chain.doFilter again, it invokes filter2
        when(request.getFilterIndexAndIncrement()).thenReturn(1, 2);
        chain.doFilter(request, response);
        verify(filter2).doFilter(request, response, chain);

        // When filter2 calls chain.doFilter again, it invokes handleAfterFilter
        when(request.getFilterIndexAndIncrement()).thenReturn(2);
        chain.doFilter(request, response);
        verify(dh).handleAfterFilter(request, response, mr);
    }

    @Test
    void doFilter_allFiltersConsumed_callsHandleAfterFilter() throws Exception {
        DispatcherHandler dh = mock(DispatcherHandler.class);
        MappingResult mr = MappingResult.notFound();
        BaseWebServerHttpRequest request = requestWithFilterIndex(0, 1);
        MappingResult.set(request, mr);

        DefaultFilterChain chain = new DefaultFilterChain(dh, Collections.singletonList(mock(WebFilter.class)));
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        // index 0 → filter called
        chain.doFilter(request, response);
        // After filter consumed, index 1 → handleAfterFilter
        when(request.getFilterIndexAndIncrement()).thenReturn(1);
        chain.doFilter(request, response);
        verify(dh).handleAfterFilter(request, response, mr);
    }

    @Test
    void doFilter_withoutDispatcherHandler_throwsNPE() {
        DefaultFilterChain chain = new DefaultFilterChain(null, Collections.emptyList());
        BaseWebServerHttpRequest request = requestWithFilterIndex(0);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        assertThrows(NullPointerException.class, () -> chain.doFilter(request, response));
    }

    // ---- helpers ----

    private static BaseWebServerHttpRequest requestWithFilterIndex(int... returns) {
        Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getFilterIndexAndIncrement()).thenReturn(
                returns.length == 1 ? returns[0] : returns[0],
                Arrays.stream(returns).skip(1).boxed().toArray(Integer[]::new));
        when(request.getRequestContext()).thenReturn(request);
        when(request.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> { fastAttrs.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(request).setAttribute(any(RequestAttribute.class), any());
        return request;
    }
}
