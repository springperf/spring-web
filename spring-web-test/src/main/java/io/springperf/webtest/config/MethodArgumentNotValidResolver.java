package io.springperf.webtest.config;

import io.springperf.web.core.exception.HandlerExceptionResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.HandlerMethod;

import java.util.stream.Collectors;

public class MethodArgumentNotValidResolver implements HandlerExceptionResolver {

    @Override
    public boolean resolveException(WebServerHttpRequest request, WebServerHttpResponse response,
                                     @Nullable HandlerMethod handler, Throwable ex) {
        if (!(ex instanceof MethodArgumentNotValidException)) {
            return false;
        }
        MethodArgumentNotValidException e = (MethodArgumentNotValidException) ex;
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        String body = "{\"error\":\"validation failed\",\"message\":\"" + msg + "\"}";
        response.sendError(HttpStatus.BAD_REQUEST, body);
        return true;
    }
}