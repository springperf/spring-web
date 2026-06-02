package io.springperf.web.core.cors.provider;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.cors.CorsConfiguration;

public interface CorsConfigurationProvider {

    @Nullable
    CorsConfiguration getCorsConfiguration(WebServerHttpRequest request, WebServerHttpResponse response);
}
