package io.springperf.web.support.mvc.arg;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpringHandlerMethodArgumentResolverAdapterTest {

    @Mock
    HandlerMethodArgumentResolver delegate;

    @Mock
    MethodParameter methodParameter;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    SpringHandlerMethodArgumentResolverAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SpringHandlerMethodArgumentResolverAdapter(delegate);
    }

    @Test
    void componentName_containsDelegateClassName() {
        assertTrue(adapter.getComponentName().contains(delegate.getClass().getName()));
    }

    @Test
    void order_isLowestPrecedence() {
        assertEquals(Ordered.LOWEST_PRECEDENCE, adapter.getOrder());
    }

    @Test
    void supportsParameter_delegatesToResolver() {
        when(delegate.supportsParameter(methodParameter)).thenReturn(true);
        assertTrue(adapter.supportsParameter(methodParameter, request, response));
        verify(delegate).supportsParameter(methodParameter);
    }

    @Test
    void supportsParameter_whenDelegateReturnsFalse() {
        when(delegate.supportsParameter(methodParameter)).thenReturn(false);
        assertFalse(adapter.supportsParameter(methodParameter, request, response));
    }

    @Test
    void resolveArgument_createsServletWrappersAndDelegates() throws Exception {
        when(delegate.resolveArgument(any(), any(), any(), any())).thenReturn("resolved");

        Object result = adapter.resolveArgument(methodParameter, request, response);

        assertEquals("resolved", result);
        verify(delegate).resolveArgument(eq(methodParameter), any(), any(NativeWebRequest.class), any());
    }
}
