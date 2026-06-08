package io.springperf.web.filter;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

/**
 * Chain of {@link WebFilter} instances that together form the filter pipeline.
 *
 * <p>Each filter invokes {@code doFilter} on the chain to pass control to the
 * next filter. When the end of the chain is reached, the actual request
 * processing begins. This follows the classic Chain-of-Responsibility
 * pattern, analogous to Servlet's {@code FilterChain}.</p>
 *
 * @since 1.0.0
 * @see WebFilter
 */
public interface FilterChain {
    /**
     * Pass the request and response to the next entity in the chain.
     *
     * @param request  the incoming HTTP request
     * @param response the outgoing HTTP response
     * @throws Exception if processing fails
     */
    void doFilter(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;
}