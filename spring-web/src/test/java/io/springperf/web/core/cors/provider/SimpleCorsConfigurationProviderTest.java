package io.springperf.web.core.cors.provider;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(MockitoExtension.class)
class SimpleCorsConfigurationProviderTest {

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Test
    void getCorsConfiguration_returnsSameInstance() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("https://example.com");
        SimpleCorsConfigurationProvider provider = new SimpleCorsConfigurationProvider(config);

        CorsConfiguration result = provider.getCorsConfiguration(request, response);

        assertSame(config, result);
    }

    @Test
    void getCorsConfiguration_ignoresRequestAndResponse() {
        CorsConfiguration config = new CorsConfiguration();
        SimpleCorsConfigurationProvider provider = new SimpleCorsConfigurationProvider(config);

        CorsConfiguration result1 = provider.getCorsConfiguration(request, response);
        CorsConfiguration result2 = provider.getCorsConfiguration(null, null);

        assertSame(config, result1);
        assertSame(config, result2);
    }
}
