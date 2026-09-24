package io.springperf.web.core.cors.provider;

import org.springframework.web.cors.CorsConfiguration;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

public class SimpleCorsConfigurationProvider implements CorsConfigurationProvider {

    private final CorsConfiguration configuration;

    public SimpleCorsConfigurationProvider(CorsConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public CorsConfiguration getCorsConfiguration(WebServerHttpRequest request, WebServerHttpResponse response) {
        return configuration;
    }
}
