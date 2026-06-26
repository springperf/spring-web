package io.springperf.web.support.codec.interceptor;

import io.springperf.web.core.codec.HttpBodyConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResponseBodyAdviceCodecInterceptorTest {

    @Mock
    ResponseBodyAdvice advice;

    @Mock
    MethodParameter methodParameter;

    @Mock
    Type targetType;

    @Mock
    HttpBodyConverter converter;

    @Mock
    HttpInputMessage inputMessage;

    @Mock
    ServerHttpRequest serverRequest;

    @Mock
    ServerHttpResponse serverResponse;

    private final Class<? extends HttpMessageConverter<?>> converterClass =
            (Class<? extends HttpMessageConverter<?>>) (Class) HttpMessageConverter.class;

    private ResponseBodyAdviceCodecInterceptor createInterceptor() {
        return new ResponseBodyAdviceCodecInterceptor(advice);
    }

    @Test
    void supportBodyRead_alwaysReturnsFalse() {
        assertFalse(createInterceptor().supportBodyRead(methodParameter, targetType, converter));
    }

    @Test
    void beforeBodyRead_returnsNull() throws Exception {
        assertNull(createInterceptor().beforeBodyRead(inputMessage, methodParameter, targetType, converter));
    }

    @Test
    void afterBodyRead_returnsNull() {
        assertNull(createInterceptor().afterBodyRead("body", inputMessage, methodParameter, targetType, converter));
    }

    @Test
    void handleEmptyBodyRead_returnsNull() {
        assertNull(createInterceptor().handleEmptyBodyRead(null, inputMessage, methodParameter, targetType, converter));
    }

    @Test
    void supportBodyWrite_delegatesToAdvice() {
        when(converter.getConverterClass()).thenReturn(converterClass);
        when(advice.supports(methodParameter, converterClass)).thenReturn(true);

        assertTrue(createInterceptor().supportBodyWrite(methodParameter, converter));

        when(advice.supports(methodParameter, converterClass)).thenReturn(false);
        assertFalse(createInterceptor().supportBodyWrite(methodParameter, converter));
    }

    @Test
    void beforeBodyWrite_delegatesToAdvice() {
        Object body = new Object();
        MediaType mediaType = MediaType.APPLICATION_JSON;
        when(converter.getConverterClass()).thenReturn(converterClass);
        when(advice.beforeBodyWrite(body, methodParameter, mediaType, converterClass, serverRequest, serverResponse))
                .thenReturn("modified");

        assertEquals("modified",
                createInterceptor().beforeBodyWrite(body, methodParameter, mediaType, converter, serverRequest, serverResponse));
    }
}