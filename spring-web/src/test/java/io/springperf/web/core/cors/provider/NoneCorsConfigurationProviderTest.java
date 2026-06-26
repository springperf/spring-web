package io.springperf.web.core.cors.provider;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class NoneCorsConfigurationProviderTest {

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    private final NoneCorsConfigurationProvider provider = new NoneCorsConfigurationProvider();

    @Test
    void alwaysReturnsNull() {
        assertNull(provider.getCorsConfiguration(request, response));
        assertNull(provider.getCorsConfiguration(null, null));
    }

    @Test
    void implementsCorsConfigurationProvider() {
        assertTrue(provider instanceof CorsConfigurationProvider);
    }
}