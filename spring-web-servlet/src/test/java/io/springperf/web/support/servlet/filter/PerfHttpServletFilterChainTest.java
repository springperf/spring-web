package io.springperf.web.support.servlet.filter;

import io.springperf.web.core.filter.FilterChain;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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

    @Test
    void doFilter_propagatesExceptionFromWrappedChain() throws Exception {
        RuntimeException cause = new RuntimeException("filter error");
        doThrow(cause).when(filterChain).doFilter(request, response);
        PerfHttpServletFilterChain chain = new PerfHttpServletFilterChain(request, response, filterChain);
        // 生产 doFilter 用 @SneakyThrows 直接委托，异常必须原样传播、不得包装或替换
        org.junit.jupiter.api.Assertions.assertSame(cause,
                org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                        () -> chain.doFilter(servletRequest, servletResponse)),
                "必须原样传播底层链异常，不得包装或替换");
    }

    // Filter 用 Wrapper 包装后继续调用链时，适配上下文必须换成新包装，
    // 否则后续参数解析/视图渲染拿到的仍是未包装的旧对象
    @Test
    void doFilter_wrappedRequestAndResponse_updatesAdapterRefs() throws Exception {
        RequestContext requestContext = mock(RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        ServletAdapterContext adapterContext = mock(ServletAdapterContext.class);
        when(requestContext.getAttribute(ServletAttribute.getAttributeKey())).thenReturn(adapterContext);
        when(adapterContext.getRequest()).thenReturn(mock(HttpServletRequest.class));
        when(adapterContext.getResponse()).thenReturn(mock(HttpServletResponse.class));
        HttpServletRequest wrappedRequest = mock(HttpServletRequest.class);
        HttpServletResponse wrappedResponse = mock(HttpServletResponse.class);

        new PerfHttpServletFilterChain(request, response, filterChain).doFilter(wrappedRequest, wrappedResponse);

        verify(adapterContext).setRequest(wrappedRequest);
        verify(adapterContext).setResponse(wrappedResponse);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_unwrappedRequestAndResponse_keepsAdapterRefs() throws Exception {
        RequestContext requestContext = mock(RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        ServletAdapterContext adapterContext = mock(ServletAdapterContext.class);
        when(requestContext.getAttribute(ServletAttribute.getAttributeKey())).thenReturn(adapterContext);
        HttpServletRequest sameRequest = mock(HttpServletRequest.class);
        HttpServletResponse sameResponse = mock(HttpServletResponse.class);
        when(adapterContext.getRequest()).thenReturn(sameRequest);
        when(adapterContext.getResponse()).thenReturn(sameResponse);

        new PerfHttpServletFilterChain(request, response, filterChain).doFilter(sameRequest, sameResponse);

        verify(adapterContext, never()).setRequest(any());
        verify(adapterContext, never()).setResponse(any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_noAdapterContextInRequestContext_delegatesOnly() throws Exception {
        RequestContext requestContext = mock(RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);

        new PerfHttpServletFilterChain(request, response, filterChain).doFilter(servletRequest, servletResponse);

        verify(filterChain).doFilter(request, response);
    }
}
