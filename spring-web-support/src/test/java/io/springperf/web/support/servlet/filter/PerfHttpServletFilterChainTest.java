package io.springperf.web.support.servlet.filter;

import io.springperf.web.filter.FilterChain;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfHttpServletFilterChainTest {

    @Mock WebServerHttpRequest request;
    @Mock WebServerHttpResponse response;
    @Mock FilterChain filterChain;
    @Mock ServletRequest servletRequest;
    @Mock ServletResponse servletResponse;

    @Test void doFilter_ignoresParamsAndDelegatesToPerfFilterChain() throws Exception {
        PerfHttpServletFilterChain chain = new PerfHttpServletFilterChain(request, response, filterChain);
        chain.doFilter(servletRequest, servletResponse);
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(servletRequest, servletResponse);
    }

    @Test void doFilter_calledMultipleTimes_delegatesEachTime() throws Exception {
        PerfHttpServletFilterChain chain = new PerfHttpServletFilterChain(request, response, filterChain);
        chain.doFilter(servletRequest, servletResponse);
        chain.doFilter(servletRequest, servletResponse);
        verify(filterChain, times(2)).doFilter(request, response);
    }

    @Test void doFilter_propagatesExceptionFromWrappedChain() throws Exception {
        doThrow(new RuntimeException("filter error")).when(filterChain).doFilter(request, response);
        PerfHttpServletFilterChain chain = new PerfHttpServletFilterChain(request, response, filterChain);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> chain.doFilter(servletRequest, servletResponse));
    }
}