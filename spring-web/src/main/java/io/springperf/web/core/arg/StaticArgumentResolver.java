package io.springperf.web.core.arg;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

/**
 * Strategy interface for resolving controller method arguments without
 * parameter metadata.
 *
 * <p>Static resolvers do not have access to {@link org.springframework.core.MethodParameter}
 * information. They are used for arguments that can be resolved purely from the request
 * and response objects, independent of the handler method signature.</p>
 *
 * <p>Common examples include resolving the {@code HttpServletRequest},
 * {@code HttpServletResponse}, {@code Principal}, or {@code Locale}.
 * Because these types require no annotation or type-specific logic per
 * method, a single static resolver handles all such parameters.</p>
 *
 * @since 1.0.0
 * @see io.springperf.web.core.arg.provider.StaticArgumentResolverProvider
 */
public interface StaticArgumentResolver {

    /**
     * Resolve the argument value from the request/response pair.
     *
     * @param request  the current HTTP request
     * @param response the current HTTP response
     * @return the resolved argument value
     * @throws Exception if argument resolution fails
     */
    Object resolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;

    /**
     * Post-process after all arguments have been resolved.
     *
     * <p>Invoked by {@link ArgumentResolverRegistry#resolveArguments} after every parameter's
     * {@link #resolveArgument} completes, allowing a resolver to perform cross-parameter logic
     * (e.g., merging {@code @ModelAttribute} bindings into the request Model).</p>
     *
     * @param args      all resolved argument values
     * @param contexts  per-argument contexts (cached per handler method)
     * @param index     the index of the argument handled by this resolver
     * @param request   the current HTTP request
     * @param response  the current HTTP response
     */
    default void postProcess(Object[] args, MethodArgContext[] contexts, int index,
                             WebServerHttpRequest request, WebServerHttpResponse response) {
    }
}