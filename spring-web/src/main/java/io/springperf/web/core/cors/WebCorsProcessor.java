package io.springperf.web.core.cors;

import io.springperf.web.context.WebComponent;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.cors.CorsConfiguration;

import java.io.IOException;

/**
 * Strategy for processing CORS requests.
 *
 * <p>Implementations evaluate the request against a
 * {@link org.springframework.web.cors.CorsConfiguration} and apply the
 * appropriate CORS response headers (e.g., {@code Access-Control-Allow-Origin}).
 * Returns {@code true} if the request is a CORS preflight that has been fully
 * handled and no further processing is needed.</p>
 *
 * @since 1.0.0
 * @see io.springperf.web.core.cors.provider.CorsConfigurationProvider
 * @see org.springframework.web.cors.CorsProcessor
 */
public interface WebCorsProcessor extends WebComponent {

    /**
     * Process a CORS request.
     *
     * @param configuration the CORS configuration, may be {@code null}
     * @param request       the incoming HTTP request
     * @param response      the outgoing HTTP response
     * @return {@code true} if the request is a handled preflight
     * @throws IOException if response writing fails
     */
    boolean process(@Nullable CorsConfiguration configuration, WebServerHttpRequest request, WebServerHttpResponse response) throws IOException;
}
