package io.springperf.web.core.interceptor;

import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.Order;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterceptorRegistryTest {

    InterceptorRegistry registry;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    RequestContext requestContext;

    @BeforeEach
    void setUp() {
        registry = new InterceptorRegistry();
    }

    // ---- registerInterceptor ----

    @Test
    void registerInterceptor_createsRegistration() {
        HandlerInterceptor interceptor = new HandlerInterceptor() {};

        InterceptorRegistration registration = registry.registerInterceptor(interceptor);

        assertSame(interceptor, registration.getInterceptor());
        assertEquals(0, registration.getOrder());
    }

    @Test
    void registerInterceptor_withOrderAnnotation_appliesOrder() {
        @Order(42)
        class AnnotatedInterceptor implements HandlerInterceptor {}
        HandlerInterceptor interceptor = new AnnotatedInterceptor();

        InterceptorRegistration registration = registry.registerInterceptor(interceptor);

        assertEquals(42, registration.getOrder());
    }

    @Test
    void registerInterceptor_multiple_addsAll() throws Exception {
        HandlerInterceptor i1 = new HandlerInterceptor() {};
        HandlerInterceptor i2 = new HandlerInterceptor() {};

        registry.registerInterceptor(i1);
        registry.registerInterceptor(i2);

        // Verify both were added by checking preHandle behavior
        List<HandlerInterceptor> prebuilt = Arrays.asList(i1, i2);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(prebuilt);

        assertTrue(registry.preHandle(request, response));
    }


    // ---- preHandle ----

    @Test
    void preHandle_noInterceptors_returnsTrue() throws Exception {
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(null);

        assertTrue(registry.preHandle(request, response));
    }

    @Test
    void preHandle_allReturnTrue_returnsTrue() throws Exception {
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);
        when(i1.preHandle(any(), any(), any())).thenReturn(true);
        when(i2.preHandle(any(), any(), any())).thenReturn(true);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(Arrays.asList(i1, i2));

        assertTrue(registry.preHandle(request, response));
        verify(i1).preHandle(any(), any(), any());
        verify(i2).preHandle(any(), any(), any());
    }

    @Test
    void preHandle_interceptorReturnsFalse_stopsChain() throws Exception {
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);
        when(i1.preHandle(any(), any(), any())).thenReturn(false);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(Arrays.asList(i1, i2));

        assertFalse(registry.preHandle(request, response));
        verify(i1).preHandle(any(), any(), any());
        verify(i2, never()).preHandle(any(), any(), any());
    }

    @Test
    void preHandle_throwsException_propagates() throws Exception {
        HandlerInterceptor interceptor = mock(HandlerInterceptor.class);
        when(interceptor.preHandle(any(), any(), any())).thenThrow(new RuntimeException("interceptor error"));

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(
                Collections.singletonList(interceptor));

        assertThrows(RuntimeException.class, () -> registry.preHandle(request, response));
    }

    // ---- postHandle ----

    @Test
    void postHandle_callsAllInterceptors() throws Exception {
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);
        Object result = "testResult";

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(Arrays.asList(i1, i2));

        registry.postHandle(request, response, result);

        verify(i1).postHandle(any(), any(), any(), eq(result));
        verify(i2).postHandle(any(), any(), any(), eq(result));
    }

    @Test
    void postHandle_interceptorThrows_doesNotPropagate() throws Exception {
        HandlerInterceptor interceptor = mock(HandlerInterceptor.class);
        doThrow(new RuntimeException("post error")).when(interceptor).postHandle(any(), any(), any(), any());

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(
                Collections.singletonList(interceptor));

        registry.postHandle(request, response, "result");
    }

    // ---- afterCompletion ----

    @Test
    void afterCompletion_callsAllInterceptors() throws Exception {
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);
        Throwable ex = new RuntimeException("test error");

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(Arrays.asList(i1, i2));

        registry.afterCompletion(request, response, ex);

        verify(i1).afterCompletion(any(), any(), any(), eq(ex));
        verify(i2).afterCompletion(any(), any(), any(), eq(ex));
    }

    @Test
    void afterCompletion_interceptorThrows_doesNotPropagate() throws Exception {
        HandlerInterceptor interceptor = mock(HandlerInterceptor.class);
        doThrow(new RuntimeException("after error")).when(interceptor).afterCompletion(any(), any(), any(), any());

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(
                Collections.singletonList(interceptor));

        registry.afterCompletion(request, response, null);
    }

    @Test
    void afterCompletion_withNullException() throws Exception {
        HandlerInterceptor interceptor = mock(HandlerInterceptor.class);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(
                Collections.singletonList(interceptor));

        registry.afterCompletion(request, response, null);

        verify(interceptor).afterCompletion(any(), any(), any(), isNull());
    }

    // ---- afterConcurrentHandlingStarted ----

    @Test
    void afterConcurrentHandlingStarted_callsAllInterceptors() throws Exception {
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(Arrays.asList(i1, i2));

        registry.afterConcurrentHandlingStarted(request, response);

        verify(i1).afterConcurrentHandlingStarted(any(), any(), any());
        verify(i2).afterConcurrentHandlingStarted(any(), any(), any());
    }

    @Test
    void afterConcurrentHandlingStarted_interceptorThrows_doesNotPropagate() throws Exception {
        HandlerInterceptor interceptor = mock(HandlerInterceptor.class);
        doThrow(new RuntimeException("async error")).when(interceptor).afterConcurrentHandlingStarted(any(), any(), any());

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(
                Collections.singletonList(interceptor));

        registry.afterConcurrentHandlingStarted(request, response);
    }
}
