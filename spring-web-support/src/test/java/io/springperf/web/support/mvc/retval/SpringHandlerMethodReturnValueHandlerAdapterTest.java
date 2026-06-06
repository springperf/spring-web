package io.springperf.web.support.mvc.retval;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpringHandlerMethodReturnValueHandlerAdapterTest {

    HandlerMethodReturnValueHandler delegate = mock(HandlerMethodReturnValueHandler.class);

    MethodParameter returnType = mock(MethodParameter.class);

    MappingHandlerMethod mappingContext = mock(MappingHandlerMethod.class);

    WebServerHttpRequest request = mock(WebServerHttpRequest.class);

    WebServerHttpResponse response = mock(WebServerHttpResponse.class);

    RequestContext requestContext = mock(RequestContext.class);

    SpringHandlerMethodReturnValueHandlerAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SpringHandlerMethodReturnValueHandlerAdapter(delegate);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(response.getCharacterEncoding()).thenReturn(Charset.forName("UTF-8"));
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
    void supportsReturnType_delegates() {
        when(delegate.supportsReturnType(returnType)).thenReturn(true);
        assertTrue(adapter.supportsReturnType(returnType, mappingContext));
        verify(delegate).supportsReturnType(returnType);
    }

    @Test
    void supportsReturnValue_whenNoMappingContext_returnsTrueForNonNull() {
        assertTrue(adapter.supportsReturnValue("any", request, response));
        assertFalse(adapter.supportsReturnValue(null, request, response));
    }

    @Test
    void resolveReturnValue_createsServletWrappersAndDelegates() throws Exception {
        Object returnValue = "test-value";

        adapter.resolveReturnValue(returnValue, returnType, request, response);

        verify(delegate).handleReturnValue(eq(returnValue), eq(returnType), any(ModelAndViewContainer.class), any(NativeWebRequest.class));
    }

    @Test
    void resolveReturnValue_mavContainerRequestHandledIsTrue() throws Exception {
        adapter.resolveReturnValue("val", returnType, request, response);

        verify(delegate).handleReturnValue(any(), any(), argThat(mav -> mav.isRequestHandled()), any());
    }
}
