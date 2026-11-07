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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.Filter;

import static org.junit.jupiter.api.Assertions.*;
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

        // 桥接层的唯一职责就是参数适配：any() 放开等于什么都没验证。
        ArgumentCaptor<javax.servlet.ServletRequest> reqCap = ArgumentCaptor
                .forClass(javax.servlet.ServletRequest.class);
        ArgumentCaptor<javax.servlet.ServletResponse> respCap = ArgumentCaptor
                .forClass(javax.servlet.ServletResponse.class);
        ArgumentCaptor<javax.servlet.FilterChain> chainCap = ArgumentCaptor
                .forClass(javax.servlet.FilterChain.class);
        verify(servletFilter).doFilter(reqCap.capture(), respCap.capture(), chainCap.capture());
        assertInstanceOf(PerfHttpServletRequest.class, reqCap.getValue(), "必须传入 servlet 请求包装，而非框架原始请求");
        assertInstanceOf(PerfHttpServletResponse.class, respCap.getValue(), "必须传入 servlet 响应包装");
        assertInstanceOf(PerfHttpServletFilterChain.class, chainCap.getValue(), "必须传入适配后的 FilterChain");
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
        // WebFilter 包装请求后必须回设委托，否则参数解析仍读旧 request
        verify(existingCtx).rebindFrameworkRequest(request);
        verify(existingCtx).rebindFrameworkResponse(response);
    }

    @Test
    void doFilterInternal_whenAdapterHasNoFilterChain_setsOne() throws Exception {
        ServletAdapterContext existingCtx = mock(ServletAdapterContext.class);
        when(existingCtx.getRequest()).thenReturn(mock(PerfHttpServletRequest.class));
        when(existingCtx.getResponse()).thenReturn(mock(PerfHttpServletResponse.class));
        // 让 mock 的 setFilterChain 真正生效，才能验证第二次 getFilterChain() 拿到新建的链
        javax.servlet.FilterChain[] current = new javax.servlet.FilterChain[1];
        when(existingCtx.getFilterChain()).thenAnswer(inv -> current[0]);
        doAnswer(inv -> {
            current[0] = inv.getArgument(0);
            return null;
        }).when(existingCtx).setFilterChain(any());
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(ServletAttribute.getAttributeKey())).thenReturn(existingCtx);

        new FilterWrapper(servletFilter).doFilter(request, response, chain);

        verify(existingCtx).setFilterChain(isA(PerfHttpServletFilterChain.class));
        // 复用 ctx 但缺 filterChain 时必须补齐后再传给 servlet filter
        verify(servletFilter).doFilter(any(), any(), isA(PerfHttpServletFilterChain.class));
    }

    @Test
    void doFilter_servletFilterThrows_shortCircuitsChain() throws Exception {
        when(request.getRequestContext()).thenReturn(requestContext);
        javax.servlet.ServletException failure = new javax.servlet.ServletException("boom");
        doThrow(failure).when(servletFilter).doFilter(any(), any(), any());

        FilterWrapper wrapper = new FilterWrapper(servletFilter);

        assertSame(failure, assertThrows(javax.servlet.ServletException.class,
                () -> wrapper.doFilter(request, response, chain)), "servlet filter 异常应原样传播");
        // servlet filter 抛异常时必须短路，不得继续放行框架链
        verify(chain, never()).doFilter(any(WebServerHttpRequest.class), any(WebServerHttpResponse.class));
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
        assertInstanceOf(PerfHttpServletRequest.class, ctx.getRequest());
        assertInstanceOf(PerfHttpServletResponse.class, ctx.getResponse());
        assertInstanceOf(PerfHttpServletFilterChain.class, ctx.getFilterChain());
        assertSame(ctx.getPerfRequest(), ctx.getRequest(), "新建时 request 应就是 perfRequest");
        assertSame(ctx.getPerfResponse(), ctx.getResponse(), "新建时 response 应就是 perfResponse");
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