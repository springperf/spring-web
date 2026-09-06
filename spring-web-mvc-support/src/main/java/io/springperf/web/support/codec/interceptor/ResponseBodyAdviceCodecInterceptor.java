package io.springperf.web.support.codec.interceptor;

import io.springperf.web.core.codec.HttpBodyConverter;
import io.springperf.web.core.codec.interceptor.HttpBodyCodecInterceptor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.io.IOException;
import java.lang.reflect.Type;

public class ResponseBodyAdviceCodecInterceptor implements HttpBodyCodecInterceptor {

    private final ResponseBodyAdvice advice;

    public ResponseBodyAdviceCodecInterceptor(ResponseBodyAdvice advice) {
        this.advice = advice;
    }

    @Override
    public boolean supportBodyRead(MethodParameter methodParameter, Type targetType, HttpBodyConverter converter) {
        return false;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, HttpBodyConverter converter) throws IOException {
        return null;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, HttpBodyConverter converter) {
        return null;
    }

    @Override
    public Object handleEmptyBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, HttpBodyConverter converter) {
        return null;
    }

    @Override
    public boolean supportBodyWrite(MethodParameter returnType, HttpBodyConverter converter) {
        return advice.supports(returnType, converter.getConverterClass());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType, HttpBodyConverter converter, ServerHttpRequest request, ServerHttpResponse response) {
        return advice.beforeBodyWrite(body, returnType, selectedContentType, converter.getConverterClass(), request, response);
    }
}
