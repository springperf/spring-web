package io.springperf.web.support.mvc.exception;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
        Throwable throwable = new Throwable("checked");

        boolean result = adapter.resolveException(request, response, handlerMethod, throwable);

        assertTrue(result);
        // Spring 的 HandlerExceptionResolver 只接收 Exception：非 Exception 的 Throwable 必须以 cause 形式包装
        ArgumentCaptor<Exception> exceptionCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(delegate).resolveException(any(), any(), any(), exceptionCaptor.capture());
        assertSame(throwable, exceptionCaptor.getValue().getCause());
    }

    @Test
    void resolveException_whenHandlerMethodIsNull_passesNullHandler() {
        when(delegate.resolveException(any(), any(), any(), any())).thenReturn(mock(ModelAndView.class));

        boolean result = adapter.resolveException(request, response, null, new RuntimeException("test"));

        assertTrue(result);
        ArgumentCaptor<Object> handlerCaptor = ArgumentCaptor.forClass(Object.class);
        verify(delegate).resolveException(any(), any(), handlerCaptor.capture(), any(Exception.class));
        assertNull(handlerCaptor.getValue(), "无 HandlerMethod 时传给 Spring resolver 的 handler 必须是 null");
    }

    @Test
    void resolveException_passesHandlerBeanNotHandlerMethod() {
        when(delegate.resolveException(any(), any(), any(), any())).thenReturn(mock(ModelAndView.class));
        Object controllerBean = new Object();
        when(handlerMethod.getBean()).thenReturn(controllerBean);

        adapter.resolveException(request, response, handlerMethod, new RuntimeException("test"));

        // Spring MVC 语义是传 handler 的 bean（controller 实例），不是 HandlerMethod 包装
        verify(delegate).resolveException(any(), any(), same(controllerBean), any(Exception.class));
    }

    @Test
    void resolveException_whenDelegateThrows_returnsFalse() {
        when(delegate.resolveException(any(), any(), any(), any())).thenThrow(new RuntimeException("delegate error"));

        boolean result = adapter.resolveException(request, response, handlerMethod, new RuntimeException("test"));

        assertFalse(result, "resolver 自身异常应被吞掉并返回 false（交下一个 resolver），不得冒泡到请求管线");
    }
}
