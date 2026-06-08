package io.springperf.web.core.cors.provider;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.cors.CorsConfiguration;

/**
 * Provides CORS configuration for a given request.
 *
 * <p>Implementations determine the applicable CORS policy for each request
 * based on its origin, path, or other criteria. The returned configuration
 * is passed to {@link io.springperf.web.core.cors.WebCorsProcessor#process} for header writing.</p>
 *
 * @since 1.0.0
 * @see io.springperf.web.core.cors.WebCorsProcessor
 */
public interface CorsConfigurationProvider {

    /**
     * Return the CORS configuration for the given request.
     *
     * @param request  the incoming HTTP request
     * @param response the outgoing HTTP response
     * @return the CORS configuration, or {@code null} if no CORS policy applies
     */
    @Nullable
    CorsConfiguration getCorsConfiguration(WebServerHttpRequest request, WebServerHttpResponse response);
}
