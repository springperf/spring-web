package io.springperf.web.core.cors.provider;

import org.springframework.web.cors.CorsConfiguration;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

public class NoneCorsConfigurationProvider implements CorsConfigurationProvider {
    @Override
    public CorsConfiguration getCorsConfiguration(WebServerHttpRequest request, WebServerHttpResponse response) {
        return null;
    }
}
