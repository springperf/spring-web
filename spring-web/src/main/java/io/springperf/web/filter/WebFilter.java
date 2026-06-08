package io.springperf.web.filter;

import io.springperf.web.context.WebComponent;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

/**
 * Contract for request interception in the filter chain.
 *
 * <p>{@code WebFilter} is the reactive-style counterpart to
 * {@link io.springperf.web.core.interceptor.HandlerInterceptor}.
 * Unlike interceptors which are tied to handler method invocation, filters
 * operate at a lower level and can short-circuit the entire request
 * processing pipeline, including static resource serving and error pages.</p>
 *
 * <p>Filters are ordered via {@link #getOrder()}. A filter may call
 * {@link FilterChain#doFilter} to pass control to the next filter in the
 * chain, or bypass it to terminate the request early (e.g., for security
 * checks or request transformation).</p>
 *
 * @since 1.0.0
 * @see FilterChain
 * @see io.springperf.web.core.interceptor.HandlerInterceptor
 */
public interface WebFilter extends WebComponent {

    int defaultOrder = 0;

    /**
     * Process the request and optionally delegate to the next filter.
     *
     * @param request  the incoming HTTP request
     * @param response the outgoing HTTP response
     * @param chain    the filter chain to pass control to the next filter
     * @throws Exception if processing fails
     */
    void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception;

    @Override
    default int getOrder() {
        return defaultOrder;
    }
}
