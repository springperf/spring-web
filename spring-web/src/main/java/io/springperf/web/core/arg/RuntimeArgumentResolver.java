package io.springperf.web.core.arg;

import io.springperf.web.context.WebComponent;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;

/**
 * Strategy interface for resolving controller method arguments at runtime.
 *
 * <p>Unlike {@link StaticArgumentResolver}, which resolves arguments without
 * inspecting the method parameter metadata, {@code RuntimeArgumentResolver}
 * works with the full {@link MethodParameter} descriptor. This allows it to
 * extract annotations, generic type information, and parameter names.</p>
 *
 * <p>Typical use cases include resolving {@code @RequestParam},
 * {@code @PathVariable}, {@code @RequestBody}, and {@code @ModelAttribute}
 * annotated parameters.</p>
 *
 * @since 1.0.0
 * @see StaticArgumentResolver
 * @see io.springperf.web.core.arg.ArgumentResolverRegistry
 */
public interface RuntimeArgumentResolver extends WebComponent {
    /**
     * Check whether this resolver supports the given parameter.
     *
     * @param parameter the method parameter descriptor (includes annotations and generic types)
     * @param request   the current HTTP request
     * @param response  the current HTTP response
     * @return {@code true} if this resolver can produce a value for the parameter
     */
    boolean supportsParameter(MethodParameter parameter, WebServerHttpRequest request, WebServerHttpResponse response);

    /**
     * Resolve the argument value for the given parameter.
     *
     * @param parameter the method parameter descriptor
     * @param request   the current HTTP request
     * @param response  the current HTTP response
     * @return the resolved argument value (may be {@code null})
     * @throws Exception if argument resolution fails
     */
    Object resolveArgument(MethodParameter parameter, WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;
}
