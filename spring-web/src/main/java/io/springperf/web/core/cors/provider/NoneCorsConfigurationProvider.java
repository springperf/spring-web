package io.springperf.web.core.cors.provider;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;

public class NoneCorsConfigurationProvider implements CorsConfigurationProvider {
    @Override
    public CorsConfiguration getCorsConfiguration(WebServerHttpRequest request, WebServerHttpResponse response) {
        return null;
    }
}
