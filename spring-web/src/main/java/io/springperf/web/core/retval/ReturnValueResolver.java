package io.springperf.web.core.retval;

import io.springperf.web.context.WebComponent;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;

/**
 * Strategy interface for resolving handler method return values into HTTP responses.
 *
 * <p>Implementations determine whether they can handle a given return type
 * (via {@link #supportsReturnType}) or a specific return value instance
 * (via {@link #supportsReturnValue}). This dual-check design allows both
 * ahead-of-time type-based resolution and runtime value-based decisions.</p>
 *
 * <p>Built-in resolvers handle common cases such as {@code ModelAndView},
 * {@code String} (view name), {@code ResponseEntity}, and {@code void}.</p>
 *
 * @since 1.0.0
 * @see ReturnValueResolverRegistry
 */
public interface ReturnValueResolver extends WebComponent {

    /**
     * Check whether this resolver supports the given return type.
     *
     * <p>This is a static check used during initialization to pre-select
     * potential resolvers for each handler method.</p>
     *
     * @param returnType     the method return type descriptor
     * @param mappingContext the handler method metadata
     * @return {@code true} if this resolver may handle the return type
     */
    boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext);

    /**
     * Check whether this resolver supports the actual return value.
     *
     * <p>This is a runtime check that complements {@link #supportsReturnType},
     * allowing resolvers to inspect the concrete return value (including
     * {@code null}) before committing to handle it.</p>
     *
     * @param returnValue the actual return value (may be {@code null})
     * @param req         the current HTTP request
     * @param resp        the current HTTP response
     * @return {@code true} if this resolver can handle the return value
     */
    boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp);

    /**
     * Resolve the return value and write it to the response.
     *
     * @param returnValue the return value to resolve
     * @param returnType  the method return type descriptor
     * @param req         the current HTTP request
     * @param resp        the current HTTP response
     * @throws Exception if resolution or response writing fails
     */
    void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception;
}
