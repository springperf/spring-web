package io.springperf.web.core.exception;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;

public class ResponseStatusExceptionResolver implements HandlerExceptionResolver, MessageSourceAware {

    private static final int MAX_CAUSE_DEPTH = 10;

    @Nullable
    private MessageSource messageSource;


    @Override
    public void setMessageSource(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public boolean resolveException(WebServerHttpRequest request, WebServerHttpResponse response, HandlerMethod handler, Throwable ex) {
        int depth = 0;
        while (ex != null && depth < MAX_CAUSE_DEPTH) {
            depth++;

            if (ex instanceof ResponseStatusException) {
                return resolveResponseStatusException((ResponseStatusException) ex, request, response, handler);
            }

            if (ex instanceof MethodArgumentNotValidException) {
                response.sendError(HttpStatus.BAD_REQUEST);
                return true;
            }

            ResponseStatus status = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
            if (status != null) {
                return resolveResponseStatus(status, request, response, handler, ex);
            }

            ex = ex.getCause();
        }
        return false;
    }

    protected boolean resolveResponseStatusException(ResponseStatusException ex, WebServerHttpRequest request, WebServerHttpResponse response, @Nullable HandlerMethod handler) {
        ex.getResponseHeaders().forEach((name, values) -> values.forEach(value -> response.getHeaders().add(name, value)));
        HttpStatus statusCode = HttpStatus.resolve(ex.getStatusCode().value());
        return applyStatusAndReason(statusCode, ex.getReason(), response);
    }

    protected boolean resolveResponseStatus(ResponseStatus responseStatus, WebServerHttpRequest request, WebServerHttpResponse response, @Nullable HandlerMethod handler, Throwable ex) {
        return applyStatusAndReason(responseStatus.code(), responseStatus.reason(), response);
    }

    protected boolean applyStatusAndReason(HttpStatus statusCode, @Nullable String reason, WebServerHttpResponse response) {
        if (StringUtils.hasLength(reason)) {
            String resolvedReason = (this.messageSource != null ? this.messageSource.getMessage(reason, null, reason, LocaleContextHolder.getLocale()) : reason);
            response.sendError(statusCode, resolvedReason);
        } else {
            response.sendError(statusCode);
        }
        return true;
    }
}
