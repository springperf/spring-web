package io.springperf.web.core.cors.provider;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.util.PathMatcher;
import org.springframework.web.cors.CorsConfiguration;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.util.PathPatternUtils;

public class RuntimeMappingCorsConfigurationProvider implements CorsConfigurationProvider {

    private final Map<String, CorsConfiguration> corsConfigurations = new LinkedHashMap<>();

    @Override
    public CorsConfiguration getCorsConfiguration(WebServerHttpRequest request, WebServerHttpResponse response) {
        PathMatcher pathMatcher = PathPatternUtils.getMatcher();
        for (Map.Entry<String, CorsConfiguration> entry : this.corsConfigurations.entrySet()) {
            if (pathMatcher.match(entry.getKey(), request.getPath())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public void addCorsConfiguration(String pathPattern, CorsConfiguration corsConfiguration) {
        corsConfigurations.put(pathPattern, corsConfiguration);
    }
}
