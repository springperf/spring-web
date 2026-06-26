package io.springperf.web.support.codec.interceptor;

import io.springperf.web.core.codec.HttpBodyConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;

import java.io.IOException;
import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestBodyAdviceCodecInterceptorTest {

    @Mock
    RequestBodyAdvice advice;

    @Mock
    MethodParameter methodParameter;

    @Mock
    Type targetType;

    @Mock
    HttpBodyConverter converter;

    @Mock
    HttpInputMessage inputMessage;

    private final Class<? extends HttpMessageConverter<?>> converterClass =
            (Class<? extends HttpMessageConverter<?>>) (Class) HttpMessageConverter.class;

    private RequestBodyAdviceCodecInterceptor createInterceptor() {
        return new RequestBodyAdviceCodecInterceptor(advice);
    }

    @Test
    void supportBodyRead_delegatesToAdvice() {
        when(converter.getConverterClass()).thenReturn(converterClass);
        when(advice.supports(methodParameter, targetType, converterClass)).thenReturn(true);

        assertTrue(createInterceptor().supportBodyRead(methodParameter, targetType, converter));

        when(advice.supports(methodParameter, targetType, converterClass)).thenReturn(false);
        assertFalse(createInterceptor().supportBodyRead(methodParameter, targetType, converter));
    }

    @Test
    void beforeBodyRead_delegatesToAdvice() throws IOException {
        when(converter.getConverterClass()).thenReturn(converterClass);
        when(advice.beforeBodyRead(inputMessage, methodParameter, targetType, converterClass))
                .thenReturn(inputMessage);

        assertSame(inputMessage,
                createInterceptor().beforeBodyRead(inputMessage, methodParameter, targetType, converter));
    }

    @Test
    void afterBodyRead_delegatesToAdvice() {
        Object body = new Object();
        when(converter.getConverterClass()).thenReturn(converterClass);
        when(advice.afterBodyRead(body, inputMessage, methodParameter, targetType, converterClass))
                .thenReturn("modified");

        assertEquals("modified",
                createInterceptor().afterBodyRead(body, inputMessage, methodParameter, targetType, converter));
    }

    @Test
    void handleEmptyBodyRead_delegatesToAdvice() {
        when(converter.getConverterClass()).thenReturn(converterClass);
        when(advice.handleEmptyBody(null, inputMessage, methodParameter, targetType, converterClass))
                .thenReturn("default");

        assertEquals("default",
                createInterceptor().handleEmptyBodyRead(null, inputMessage, methodParameter, targetType, converter));
    }

    @Test
    void supportBodyWrite_alwaysReturnsFalse() {
        assertFalse(createInterceptor().supportBodyWrite(methodParameter, converter));
    }

    @Test
    void beforeBodyWrite_returnsNull() {
        assertNull(createInterceptor().beforeBodyWrite("body", methodParameter, null, converter, null, null));
    }
}