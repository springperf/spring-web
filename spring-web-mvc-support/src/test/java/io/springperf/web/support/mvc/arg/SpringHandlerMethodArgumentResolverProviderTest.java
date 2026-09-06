package io.springperf.web.support.mvc.arg;

import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringHandlerMethodArgumentResolverProviderTest {

    @Mock
    HandlerMethodArgumentResolver delegate;

    @Mock
    MethodParameter methodParameter;

    @Mock
    MappingHandlerMethod mappingContext;

    SpringHandlerMethodArgumentResolverProvider provider;

    @BeforeEach
    void setUp() {
        provider = new SpringHandlerMethodArgumentResolverProvider(delegate);
    }

    @Test
    void componentName_containsDelegateClassName() {
        assertTrue(provider.getComponentName().contains(delegate.getClass().getName()));
    }

    @Test
    void supports_delegatesToResolver() {
        when(delegate.supportsParameter(methodParameter)).thenReturn(true);
        assertTrue(provider.supports(methodParameter, mappingContext));
        verify(delegate).supportsParameter(methodParameter);
    }

    @Test
    void supports_whenDelegateReturnsFalse() {
        when(delegate.supportsParameter(methodParameter)).thenReturn(false);
        assertFalse(provider.supports(methodParameter, mappingContext));
    }

    @Test
    void getResolver_returnsResolverThatDelegates() throws Exception {
        // 验证返回的 StaticArgumentResolver 真正委托给 Spring 的 HandlerMethodArgumentResolver
        io.springperf.web.http.RequestContext requestContext = mock(io.springperf.web.http.RequestContext.class);
        jakarta.servlet.http.HttpServletRequest servletRequest = mock(jakarta.servlet.http.HttpServletRequest.class);
        jakarta.servlet.http.HttpServletResponse servletResponse = mock(jakarta.servlet.http.HttpServletResponse.class);
        io.springperf.web.support.servlet.context.ServletAdapterContext adapter =
                new io.springperf.web.support.servlet.context.ServletAdapterContext(
                        mock(io.springperf.web.support.servlet.PerfHttpServletRequest.class),
                        mock(io.springperf.web.support.servlet.PerfHttpServletResponse.class), null);
        adapter.setRequest(servletRequest);
        adapter.setResponse(servletResponse);
        when(requestContext.getAttribute(io.springperf.web.support.servlet.ServletAttribute.getAttributeKey()))
                .thenReturn(adapter);

        io.springperf.web.http.WebServerHttpRequest request = mock(io.springperf.web.http.WebServerHttpRequest.class);
        io.springperf.web.http.WebServerHttpResponse response = mock(io.springperf.web.http.WebServerHttpResponse.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(delegate.resolveArgument(eq(methodParameter), any(), any(), any())).thenReturn("resolved-value");

        StaticArgumentResolver staticResolver = provider.getResolver(methodParameter, mappingContext, null);
        Object result = staticResolver.resolveArgument(request, response);

        assertEquals("resolved-value", result, "应把参数解析委托给 Spring resolver");
        verify(delegate).resolveArgument(eq(methodParameter), any(), any(), any());
    }

    @Test
    void getOrder_defaultsToLowestPrecedence_whenResolverHasNoOrder() {
        assertEquals(Ordered.LOWEST_PRECEDENCE, provider.getOrder());
    }

    @Test
    void getOrder_usesResolverOrder_whenResolverImplementsOrdered() {
        HandlerMethodArgumentResolver orderedResolver = new OrderedHandlerMethodArgumentResolver(50);
        SpringHandlerMethodArgumentResolverProvider orderedProvider =
                new SpringHandlerMethodArgumentResolverProvider(orderedResolver);
        assertEquals(50, orderedProvider.getOrder());
    }

    private static class OrderedHandlerMethodArgumentResolver implements HandlerMethodArgumentResolver, Ordered {
        private final int order;

        OrderedHandlerMethodArgumentResolver(int order) {
            this.order = order;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return false;
        }

        @Override
        public Object resolveArgument(MethodParameter parameter,
                                       org.springframework.web.method.support.ModelAndViewContainer mavContainer,
                                       org.springframework.web.context.request.NativeWebRequest webRequest,
                                       org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
            return null;
        }
    }
}