package io.springperf.web.support.codec.interceptor;

import io.springperf.web.core.codec.HttpBodyConverter;
import io.springperf.web.core.codec.interceptor.HttpBodyCodecInterceptor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;

import java.io.IOException;
import java.lang.reflect.Type;

public class RequestBodyAdviceCodecInterceptor implements HttpBodyCodecInterceptor {

    private final RequestBodyAdvice advice;

    public RequestBodyAdviceCodecInterceptor(RequestBodyAdvice advice) {
        this.advice = advice;
    }

    @Override
    public boolean supportBodyRead(MethodParameter methodParameter, Type targetType, HttpBodyConverter converter) {
        return advice.supports(methodParameter, targetType, converter.getConverterClass());
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, HttpBodyConverter converter) throws IOException {
        return advice.beforeBodyRead(inputMessage, parameter, targetType, converter.getConverterClass());
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, HttpBodyConverter converter) {
        return advice.afterBodyRead(body, inputMessage, parameter, targetType, converter.getConverterClass());
    }

    @Override
    public Object handleEmptyBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, HttpBodyConverter converter) {
        return advice.handleEmptyBody(body, inputMessage, parameter, targetType, converter.getConverterClass());
    }

    @Override
    public boolean supportBodyWrite(MethodParameter returnType, HttpBodyConverter converter) {
        return false;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType, HttpBodyConverter converter, ServerHttpRequest request, ServerHttpResponse response) {
        return null;
    }
}
