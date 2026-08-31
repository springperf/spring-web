package io.springperf.web.core.cors;

import io.springperf.web.core.cors.provider.CorsConfigurationProvider;
import io.springperf.web.core.cors.provider.NoneCorsConfigurationProvider;
import io.springperf.web.core.cors.provider.SimpleCorsConfigurationProvider;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CorsRegistryTest {

    @Mock WebServerHttpRequest request;
    @Mock WebServerHttpResponse response;
    @Mock WebCorsProcessor processor;
    @Mock RequestContext requestContext;

    private CorsRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(request.getRequestContext()).thenReturn(requestContext);
        lenient().when(requestContext.getAttribute(any(io.springperf.web.http.RequestAttribute.class))).thenReturn(null);

        registry = new CorsRegistry();
        Field processorField = CorsRegistry.class.getDeclaredField("webCorsProcessor");
        processorField.setAccessible(true);
        processorField.set(registry, processor);
    }

    @Test
    void addMapping_returnsRegistrationWithPath() {
        CorsRegistration registration = registry.addMapping("/api/**");
        assertNotNull(registration);
        assertEquals("/api/**", registration.getPathPattern());
    }

    @Test
    void addMapping_multipleRegistrations_eachBuildsIndependentRegistration() {
        CorsRegistration r1 = registry.addMapping("/api/**");
        CorsRegistration r2 = registry.addMapping("/admin/**");
        CorsRegistration r3 = registry.addMapping("/public/**");

        // addMapping 每次都构建独立注册（路径不同，实例不同）
        assertNotSame(r1, r2);
        assertNotSame(r2, r3);
        assertEquals("/api/**", r1.getPathPattern());
        assertEquals("/admin/**", r2.getPathPattern());
        assertEquals("/public/**", r3.getPathPattern());
    }

    @Test
    void addActuatorCorsConfiguration_storesRegistration() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("http://example.com");
        config.addAllowedMethod("GET");

        registry.addActuatorCorsConfiguration("/actuator/**", config);

        List<CorsRegistration> registrations = getRegistrations();
        assertEquals(1, registrations.size());
        assertEquals("/actuator/**", registrations.get(0).getPathPattern());
        assertSame(config, registrations.get(0).getCorsConfiguration(), "预构建配置应原样保存");
    }

    @Test
    void addActuatorCorsConfiguration_multipleEntries() {
        CorsConfiguration config1 = new CorsConfiguration();
        config1.addAllowedOrigin("http://example.com");
        CorsConfiguration config2 = new CorsConfiguration();
        config2.addAllowedOrigin("http://admin.com");

        registry.addActuatorCorsConfiguration("/actuator/health", config1);
        registry.addActuatorCorsConfiguration("/actuator/info", config2);

        List<CorsRegistration> registrations = getRegistrations();
        assertEquals(2, registrations.size());
        assertEquals(new HashSet<>(Arrays.asList("/actuator/health", "/actuator/info")),
                registrations.stream().map(r -> r.getPathPattern()).collect(Collectors.toSet()));
    }

    private List<CorsRegistration> getRegistrations() {
        try {
            Field field = CorsRegistry.class.getDeclaredField("registrations");
            field.setAccessible(true);
            return (List<CorsRegistration>) field.get(registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void corsHandle_nonPreflightNoContext_returnsFalse() throws Exception {
        when(request.getMethod()).thenReturn(HttpMethod.GET);

        boolean result = registry.corsHandle(request, response);

        assertFalse(result);
    }

    @Test
    void corsHandle_preflightNullConfig_callsProcessorAndReturnsTrue() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://example.com");
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getMethod()).thenReturn(HttpMethod.OPTIONS);

        boolean result = registry.corsHandle(request, response);

        assertTrue(result);
        verify(processor).process(null, request, response);
    }

    @Test
    void corsHandle_nonPreflightWithConfig_callsProcessor() throws Exception {
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(processor.process(any(), any(), any())).thenReturn(true);

        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("http://example.com");
        SimpleCorsConfigurationProvider provider = new SimpleCorsConfigurationProvider(config);

        CorsRegistry spy = spy(registry);
        doReturn(provider).when(spy).getCorsConfigurationProvider(request);

        boolean result = spy.corsHandle(request, response);

        verify(processor).process(any(CorsConfiguration.class), eq(request), eq(response));
    }

    @Test
    void corsHandle_preflightWithConfig_callsProcessorAndReturnsTrue() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://example.com");
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getMethod()).thenReturn(HttpMethod.OPTIONS);

        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("http://example.com");
        SimpleCorsConfigurationProvider provider = new SimpleCorsConfigurationProvider(config);

        CorsRegistry spy = spy(registry);
        doReturn(provider).when(spy).getCorsConfigurationProvider(request);

        boolean result = spy.corsHandle(request, response);

        assertTrue(result);
        verify(processor).process(any(CorsConfiguration.class), eq(request), eq(response));
    }

    @Test
    void corsHandle_actualRequestConfigDenied_processorReturnsFalse() throws Exception {
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(processor.process(any(), any(), any())).thenReturn(false);

        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("http://example.com");
        SimpleCorsConfigurationProvider provider = new SimpleCorsConfigurationProvider(config);

        CorsRegistry spy = spy(registry);
        doReturn(provider).when(spy).getCorsConfigurationProvider(request);

        boolean result = spy.corsHandle(request, response);

        assertTrue(result);
    }

    @Test
    void getCorsConfiguration_nullContext_returnsNull() {
        CorsConfiguration config = registry.getCorsConfiguration(request, response);

        assertNull(config);
    }

    @Test
    void getCorsConfigurationProvider_nullContext_returnsNoneProvider() {
        CorsConfigurationProvider provider = registry.getCorsConfigurationProvider(request);

        assertTrue(provider instanceof NoneCorsConfigurationProvider);
        assertNull(provider.getCorsConfiguration(request, response));
    }
}