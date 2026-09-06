package io.springperf.web.support.mvc.exception;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.Ordered;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpringHandlerExceptionResolverAdapterTest {

    @Mock
    org.springframework.web.servlet.HandlerExceptionResolver delegate;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    HandlerMethod handlerMethod;

    SpringHandlerExceptionResolverAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SpringHandlerExceptionResolverAdapter(delegate);
    }

    @Test
    void componentName_containsDelegateClassName() {
        assertTrue(adapter.getComponentName().contains(delegate.getClass().getName()));
    }

    @Test
    void order_isLowestPrecedenceMinus10000() {
        assertEquals(Ordered.LOWEST_PRECEDENCE - 10000, adapter.getOrder());
    }

    @Test
    void resolveException_whenDelegateReturnsModelAndView_returnsTrue() {
        when(delegate.resolveException(any(), any(), any(), any())).thenReturn(mock(ModelAndView.class));

        boolean result = adapter.resolveException(request, response, handlerMethod, new RuntimeException("test"));

        assertTrue(result);
    }

    @Test
    void resolveException_whenDelegateReturnsNull_returnsFalse() {
        when(delegate.resolveException(any(), any(), any(), any())).thenReturn(null);

        boolean result = adapter.resolveException(request, response, handlerMethod, new RuntimeException("test"));

        assertFalse(result);
    }

    @Test
    void resolveException_convertsThrowableToException() {
        when(delegate.resolveException(any(), any(), any(), any())).thenReturn(mock(ModelAndView.class));

        boolean result = adapter.resolveException(request, response, handlerMethod, new Throwable("checked"));

        assertTrue(result);
    }

    @Test
    void resolveException_whenHandlerMethodIsNull_usesNullHandler() {
        when(delegate.resolveException(any(), any(), any(), any())).thenReturn(mock(ModelAndView.class));

        boolean result = adapter.resolveException(request, response, null, new RuntimeException("test"));

        assertTrue(result);
    }

    @Test
    void resolveException_whenDelegateThrows_returnsFalse() {
        when(delegate.resolveException(any(), any(), any(), any())).thenThrow(new RuntimeException("delegate error"));

        boolean result = adapter.resolveException(request, response, handlerMethod, new RuntimeException("test"));

        assertFalse(result);
    }
}
