package io.springperf.web.core.cors.provider;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuntimeMappingCorsConfigurationProviderTest {

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Test
    void getCorsConfiguration_noMatch_returnsNull() {
        RuntimeMappingCorsConfigurationProvider provider = new RuntimeMappingCorsConfigurationProvider();
        provider.addCorsConfiguration("/api/**", new CorsConfiguration());

        when(request.getPath()).thenReturn("/admin/users");

        assertNull(provider.getCorsConfiguration(request, response));
    }

    @Test
    void getCorsConfiguration_emptyMappings_returnsNull() {
        RuntimeMappingCorsConfigurationProvider provider = new RuntimeMappingCorsConfigurationProvider();

        assertNull(provider.getCorsConfiguration(request, response));
    }

    @Test
    void getCorsConfiguration_exactMatch_returnsConfig() {
        RuntimeMappingCorsConfigurationProvider provider = new RuntimeMappingCorsConfigurationProvider();
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("https://example.com");
        provider.addCorsConfiguration("/api/users", config);

        when(request.getPath()).thenReturn("/api/users");

        assertSame(config, provider.getCorsConfiguration(request, response));
    }

    @Test
    void getCorsConfiguration_wildcardMatch_returnsConfig() {
        RuntimeMappingCorsConfigurationProvider provider = new RuntimeMappingCorsConfigurationProvider();
        CorsConfiguration config = new CorsConfiguration();
        provider.addCorsConfiguration("/api/**", config);

        when(request.getPath()).thenReturn("/api/users/123");

        assertSame(config, provider.getCorsConfiguration(request, response));
    }

    @Test
    void getCorsConfiguration_firstMatchWins() {
        RuntimeMappingCorsConfigurationProvider provider = new RuntimeMappingCorsConfigurationProvider();
        CorsConfiguration specificConfig = new CorsConfiguration();
        specificConfig.addAllowedOrigin("https://specific.com");
        CorsConfiguration generalConfig = new CorsConfiguration();
        generalConfig.addAllowedOrigin("https://general.com");

        provider.addCorsConfiguration("/api/users", specificConfig);
        provider.addCorsConfiguration("/api/**", generalConfig);

        when(request.getPath()).thenReturn("/api/users");

        assertSame(specificConfig, provider.getCorsConfiguration(request, response));
    }

    @Test
    void getCorsConfiguration_multiplePatterns_secondMatches() {
        RuntimeMappingCorsConfigurationProvider provider = new RuntimeMappingCorsConfigurationProvider();
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("https://example.com");

        provider.addCorsConfiguration("/admin/**", new CorsConfiguration());
        provider.addCorsConfiguration("/api/**", config);

        when(request.getPath()).thenReturn("/api/users");

        assertSame(config, provider.getCorsConfiguration(request, response));
    }

    @Test
    void addCorsConfiguration_overwritesExisting() {
        RuntimeMappingCorsConfigurationProvider provider = new RuntimeMappingCorsConfigurationProvider();
        CorsConfiguration oldConfig = new CorsConfiguration();
        CorsConfiguration newConfig = new CorsConfiguration();
        newConfig.addAllowedOrigin("https://new.com");

        provider.addCorsConfiguration("/api/**", oldConfig);
        provider.addCorsConfiguration("/api/**", newConfig);

        when(request.getPath()).thenReturn("/api/users");

        assertSame(newConfig, provider.getCorsConfiguration(request, response));
    }
}