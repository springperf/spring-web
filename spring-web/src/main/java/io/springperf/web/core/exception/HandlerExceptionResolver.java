package io.springperf.web.core.exception;

import io.springperf.web.context.WebComponent;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.method.HandlerMethod;

/**
 * Strategy interface for resolving exceptions thrown during handler execution.
 * <p>
 * Implementations can produce custom error responses or perform logging
 * when a controller method throws an exception.
 */
public interface HandlerExceptionResolver extends WebComponent {

    /**
     * Resolves the given exception and optionally writes an error response.
     *
     * @param request  the current HTTP request
     * @param response the current HTTP response
     * @param handler  the handler method that threw the exception, may be {@code null}
     * @param ex       the exception to resolve
     * @return {@code true} if the exception was successfully resolved
     */
    boolean resolveException(WebServerHttpRequest request, WebServerHttpResponse response, @Nullable HandlerMethod handler, Throwable ex);
}
