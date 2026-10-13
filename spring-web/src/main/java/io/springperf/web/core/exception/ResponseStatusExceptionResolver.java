package io.springperf.web.core.exception;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.server.ErrorResponseConfig;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

            if (ex instanceof org.springframework.web.context.request.async.AsyncRequestTimeoutException) {
                // 对齐 Spring DefaultHandlerExceptionResolver#handleAsyncRequestTimeoutException：
                // 异步请求超时 → 503 Service Unavailable（否则落到 500 兜底，客户端拿不到可判别的超时语义）。
                // 响应超时（server.http.timeout）可能已先行写出 504，此时不得再改写已提交的响应。
                if (!response.isCommitted()) {
                    response.sendError(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex,
                            ErrorResponseConfig.isParamPresent(request, "trace"),
                            ErrorResponseConfig.isParamPresent(request, "message"),
                            ErrorResponseConfig.isParamPresent(request, "errors"));
                }
                return true;
            }

            if (ex instanceof MethodArgumentNotValidException
                    || ex instanceof MethodArgumentTypeMismatchException
                    || ex instanceof HttpMessageNotReadableException) {
                // 对齐 Spring DefaultHandlerExceptionResolver：参数绑定/消息体解析错误 → 400。
                // 必须把原始异常作为 cause 传给错误渲染：MethodArgumentNotValidException 携带
                // BindingResult，是 include-binding-errors 唯一的数据来源（丢弃 cause 会让该策略失效）。
                response.sendError(HttpStatus.BAD_REQUEST, ex.getMessage(), ex,
                        ErrorResponseConfig.isParamPresent(request, "trace"),
                        ErrorResponseConfig.isParamPresent(request, "message"),
                        ErrorResponseConfig.isParamPresent(request, "errors"));
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
        // 2.7.x（Spring 5.3）：ResponseStatusException#getResponseHeaders() 存在，无需版本适配层
        return applyStatusAndReason(ex.getStatus(), ex.getReason(), response, request);
    }

    protected boolean resolveResponseStatus(ResponseStatus responseStatus, WebServerHttpRequest request, WebServerHttpResponse response, @Nullable HandlerMethod handler, Throwable ex) {
        return applyStatusAndReason(responseStatus.code(), responseStatus.reason(), response, request);
    }

    protected boolean applyStatusAndReason(HttpStatus statusCode, @Nullable String reason, WebServerHttpResponse response) {
        return applyStatusAndReason(statusCode, reason, response, null);
    }

    protected boolean applyStatusAndReason(HttpStatus statusCode, @Nullable String reason,
            WebServerHttpResponse response, @Nullable WebServerHttpRequest request) {
        // on-param 模式：由请求参数 trace / message / errors 是否命中决定是否暴露（参数名对齐 Boot
        // AbstractErrorController#isParamPresent）
        boolean traceParam = ErrorResponseConfig.isParamPresent(request, "trace");
        boolean messageParam = ErrorResponseConfig.isParamPresent(request, "message");
        boolean errorsParam = ErrorResponseConfig.isParamPresent(request, "errors");
        if (StringUtils.hasLength(reason)) {
            String resolvedReason = (this.messageSource != null ? this.messageSource.getMessage(reason, null, reason, LocaleContextHolder.getLocale()) : reason);
            response.sendError(statusCode, resolvedReason, null, traceParam, messageParam, errorsParam);
        } else {
            response.sendError(statusCode, null, null, traceParam, messageParam, errorsParam);
        }
        return true;
    }
}
