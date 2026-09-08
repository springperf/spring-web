package org.springframework.web.servlet.config.annotation;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.*;

class CorsRegistrationTest {

    @Test
    void constructor_setsPathPattern() {
        CorsRegistration reg = new CorsRegistration("/api/**");
        assertEquals("/api/**", reg.getPathPattern());
    }

    @Test
    void constructor_appliesPermitDefaultValues() {
        CorsRegistration reg = new CorsRegistration("/api/**");
        CorsConfiguration config = reg.getCorsConfiguration();
        assertNotNull(config.getAllowedOrigins());
        assertTrue(config.getAllowedOrigins().contains("*"));
    }

    @Test
    void allowedOrigins_setsOrigins() {
        CorsRegistration reg = new CorsRegistration("/api/**").allowedOrigins("https://example.com");
        assertTrue(reg.getCorsConfiguration().getAllowedOrigins().contains("https://example.com"));
    }

    @Test
    void allowedMethods_setsMethods() {
        CorsRegistration reg = new CorsRegistration("/api/**").allowedMethods("GET", "POST");
        assertTrue(reg.getCorsConfiguration().getAllowedMethods().contains("GET"));
    }

    @Test
    void allowedHeaders_setsHeaders() {
        CorsRegistration reg = new CorsRegistration("/api/**").allowedHeaders("X-Custom");
        assertTrue(reg.getCorsConfiguration().getAllowedHeaders().contains("X-Custom"));
    }

    @Test
    void exposedHeaders_setsHeaders() {
        CorsRegistration reg = new CorsRegistration("/api/**").exposedHeaders("X-Result");
        assertTrue(reg.getCorsConfiguration().getExposedHeaders().contains("X-Result"));
    }

    @Test
    void allowCredentials_setsFlag() {
        CorsRegistration reg = new CorsRegistration("/api/**").allowCredentials(true);
        assertTrue(reg.getCorsConfiguration().getAllowCredentials());
    }

    @Test
    void maxAge_setsMaxAge() {
        CorsRegistration reg = new CorsRegistration("/api/**").maxAge(3600L);
        assertEquals(3600L, reg.getCorsConfiguration().getMaxAge().longValue());
    }

    @Test
    void chainedCalls_returnsSelf() {
        CorsRegistration reg = new CorsRegistration("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET")
                .allowedHeaders("X-Hdr")
                .exposedHeaders("X-Res")
                .allowCredentials(false)
                .maxAge(100);
        assertNotNull(reg);
        assertEquals("/api/**", reg.getPathPattern());
    }
}