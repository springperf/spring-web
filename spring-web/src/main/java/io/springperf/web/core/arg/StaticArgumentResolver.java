package io.springperf.web.core.arg;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

/**
 * Strategy interface for resolving controller method arguments without
 * parameter metadata.
 *
 * <p>Unlike {@link RuntimeArgumentResolver}, static resolvers do not have
 * access to {@link org.springframework.core.MethodParameter} information.
 * They are used for arguments that can be resolved purely from the request
 * and response objects, independent of the handler method signature.</p>
 *
 * <p>Common examples include resolving the {@code HttpServletRequest},
 * {@code HttpServletResponse}, {@code Principal}, or {@code Locale}.
 * Because these types require no annotation or type-specific logic per
 * method, a single static resolver handles all such parameters.</p>
 *
 * @since 1.0.0
 * @see RuntimeArgumentResolver
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
}

