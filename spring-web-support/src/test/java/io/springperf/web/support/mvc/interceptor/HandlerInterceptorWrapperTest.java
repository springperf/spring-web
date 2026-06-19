package io.springperf.web.support.mvc.interceptor;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HandlerInterceptorWrapperTest {

    @Mock
    org.springframework.web.servlet.HandlerInterceptor springInterceptor;

    @Mock
    WebServerHttpRequest serverRequest;

    @Mock
    WebServerHttpResponse serverResponse;

    @Mock
    HttpServletRequest servletRequest;

    @Mock
    HttpServletResponse servletResponse;

    @Captor
    ArgumentCaptor<Exception> exceptionCaptor;

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void setupRequestAttributes() {
        ServletRequestAttributes attrs = new ServletRequestAttributes(servletRequest, servletResponse);
        RequestContextHolder.setRequestAttributes(attrs);
    }

    @Test
    void preHandle_delegatesToSpringInterceptor() throws Exception {
        setupRequestAttributes();
        when(springInterceptor.preHandle(servletRequest, servletResponse, "handler")).thenReturn(false);

        HandlerInterceptorWrapper wrapper = new HandlerInterceptorWrapper(springInterceptor);
        boolean result = wrapper.preHandle(serverRequest, serverResponse, "handler");

        assertFalse(result);
        verify(springInterceptor).preHandle(servletRequest, servletResponse, "handler");
    }

    @Test
    void preHandle_returnsTrueByDefault() throws Exception {
        setupRequestAttributes();
        when(springInterceptor.preHandle(servletRequest, servletResponse, "handler")).thenReturn(true);

        HandlerInterceptorWrapper wrapper = new HandlerInterceptorWrapper(springInterceptor);
        boolean result = wrapper.preHandle(serverRequest, serverResponse, "handler");

        assertTrue(result);
    }

    @Test
    void postHandle_delegatesWithNullModelAndView() throws Exception {
        setupRequestAttributes();

        HandlerInterceptorWrapper wrapper = new HandlerInterceptorWrapper(springInterceptor);
        wrapper.postHandle(serverRequest, serverResponse, "handler", "result");

        verify(springInterceptor).postHandle(servletRequest, servletResponse, "handler", null);
    }

    @Test
    void afterCompletion_withException_delegatesException() throws Exception {
        setupRequestAttributes();
        Exception ex = new IllegalArgumentException("test error");

        HandlerInterceptorWrapper wrapper = new HandlerInterceptorWrapper(springInterceptor);
        wrapper.afterCompletion(serverRequest, serverResponse, "handler", ex);

        verify(springInterceptor).afterCompletion(servletRequest, servletResponse, "handler", ex);
    }

    @Test
    void afterCompletion_withError_wrapsInNestedServletException() throws Exception {
        setupRequestAttributes();
        Error error = new StackOverflowError("fatal");

        HandlerInterceptorWrapper wrapper = new HandlerInterceptorWrapper(springInterceptor);
        wrapper.afterCompletion(serverRequest, serverResponse, "handler", error);

        verify(springInterceptor).afterCompletion(eq(servletRequest), eq(servletResponse), eq("handler"), exceptionCaptor.capture());
        Exception captured = exceptionCaptor.getValue();
        assertInstanceOf(org.springframework.web.util.NestedServletException.class, captured);
        assertSame(error, captured.getCause());
    }

    @Test
    void afterCompletion_withNullThrowable_createsNestedServletException() throws Exception {
        setupRequestAttributes();

        HandlerInterceptorWrapper wrapper = new HandlerInterceptorWrapper(springInterceptor);
        wrapper.afterCompletion(serverRequest, serverResponse, "handler", null);

        verify(springInterceptor).afterCompletion(eq(servletRequest), eq(servletResponse), eq("handler"), exceptionCaptor.capture());
        Exception captured = exceptionCaptor.getValue();
        assertInstanceOf(org.springframework.web.util.NestedServletException.class, captured);
        assertNull(captured.getCause());
    }

    @Test
    void afterConcurrentHandlingStarted_nonAsyncInterceptor_doesNothing() throws Exception {
        setupRequestAttributes();

        HandlerInterceptorWrapper wrapper = new HandlerInterceptorWrapper(springInterceptor);
        wrapper.afterConcurrentHandlingStarted(serverRequest, serverResponse, "handler");
        // no interaction with springInterceptor since it's not AsyncHandlerInterceptor
        verifyNoInteractions(springInterceptor);
    }

    @Test
    void afterConcurrentHandlingStarted_asyncInterceptor_delegates() throws Exception {
        setupRequestAttributes();
        AsyncHandlerInterceptor asyncInterceptor = mock(AsyncHandlerInterceptor.class);

        HandlerInterceptorWrapper wrapper = new HandlerInterceptorWrapper(asyncInterceptor);
        wrapper.afterConcurrentHandlingStarted(serverRequest, serverResponse, "handler");

        verify(asyncInterceptor).afterConcurrentHandlingStarted(servletRequest, servletResponse, "handler");
    }

    @Test
    void toString_containsInterceptorName() {
        when(springInterceptor.toString()).thenReturn("MyTestInterceptor");

        HandlerInterceptorWrapper wrapper = new HandlerInterceptorWrapper(springInterceptor);

        assertTrue(wrapper.toString().contains("MyTestInterceptor"));
    }

    @Test
    void preHandle_nullRequestAttributes_returnsTrue() throws Exception {
        // Don't set up RequestContextHolder - wrapper should log a warning and return true
        HandlerInterceptorWrapper wrapper = new HandlerInterceptorWrapper(springInterceptor);

        boolean result = wrapper.preHandle(serverRequest, serverResponse, "handler");
        assertTrue(result, "preHandle should return true when RequestAttributes is null");
    }
}
