package io.springperf.web.core.cors;

import io.springperf.web.util.support.ContainmentResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.*;

class CorsRegistrationTest {

    @Test
    void constructor_setsPathPattern() {
        CorsRegistration registration = new CorsRegistration("/api/**");
        assertEquals("/api/**", registration.getPathPattern());
    }

    @Test
    void constructor_defaultConfig_appliesPermitDefaultValues() {
        CorsRegistration registration = new CorsRegistration("/api/**");
        CorsConfiguration config = registration.getCorsConfiguration();

        assertNotNull(config);
        // applyPermitDefaultValues sets allowed origins to *
        assertArrayEquals(new String[]{"*"}, config.getAllowedOrigins().toArray());
    }

    @Test
    void allowedOrigins_setsOrigins() {
        CorsRegistration registration = new CorsRegistration("/api/**");
        CorsRegistration result = registration.allowedOrigins("https://domain1.com", "https://domain2.com");

        assertSame(registration, result);
        assertArrayEquals(new String[]{"https://domain1.com", "https://domain2.com"},
                registration.getCorsConfiguration().getAllowedOrigins().toArray());
    }

    @Test
    void allowedMethods_setsMethods() {
        CorsRegistration registration = new CorsRegistration("/api/**");
        CorsRegistration result = registration.allowedMethods("GET", "POST");

        assertSame(registration, result);
        assertArrayEquals(new String[]{"GET", "POST"},
                registration.getCorsConfiguration().getAllowedMethods().toArray());
    }

    @Test
    void allowedHeaders_setsHeaders() {
        CorsRegistration registration = new CorsRegistration("/api/**");
        CorsRegistration result = registration.allowedHeaders("X-Custom", "Authorization");

        assertSame(registration, result);
        assertArrayEquals(new String[]{"X-Custom", "Authorization"},
                registration.getCorsConfiguration().getAllowedHeaders().toArray());
    }

    @Test
    void exposedHeaders_setsHeaders() {
        CorsRegistration registration = new CorsRegistration("/api/**");
        CorsRegistration result = registration.exposedHeaders("X-Result", "X-Debug");

        assertSame(registration, result);
        assertArrayEquals(new String[]{"X-Result", "X-Debug"},
                registration.getCorsConfiguration().getExposedHeaders().toArray());
    }

    @Test
    void allowCredentials_setsFlag() {
        CorsRegistration registration = new CorsRegistration("/api/**");
        CorsRegistration result = registration.allowCredentials(true);

        assertSame(registration, result);
        assertTrue(registration.getCorsConfiguration().getAllowCredentials());
    }

    @Test
    void allowCredentials_false() {
        CorsRegistration registration = new CorsRegistration("/api/**");
        registration.allowCredentials(false);

        assertFalse(registration.getCorsConfiguration().getAllowCredentials());
    }

    @Test
    void maxAge_setsMaxAge() {
        CorsRegistration registration = new CorsRegistration("/api/**");
        CorsRegistration result = registration.maxAge(3600L);

        assertSame(registration, result);
        assertEquals(Long.valueOf(3600), registration.getCorsConfiguration().getMaxAge());
    }

    @Test
    void order_setsOrder() {
        CorsRegistration registration = new CorsRegistration("/api/**");
        CorsRegistration result = registration.order(100);

        assertSame(registration, result);
        assertEquals(100, registration.getOrder());
    }

    @Test
    void defaultOrder_isLowestPrecedenceMinus10000() {
        CorsRegistration registration = new CorsRegistration("/api/**");

        assertEquals(Ordered.LOWEST_PRECEDENCE - 10000, registration.getOrder());
    }

    @Test
    void matchPathRuleToCached_alwaysMatch() {
        CorsRegistration registration = new CorsRegistration("/api/**");
        // "/api/**" should always contain "/api/users"
        assertEquals(ContainmentResult.ALWAYS, registration.matchPathRuleToCached("/api/users"));
    }

    @Test
    void matchPathRuleToCached_noMatch() {
        CorsRegistration registration = new CorsRegistration("/admin/**");
        // "/admin/**" should not contain "/api/users"
        assertEquals(ContainmentResult.NEVER, registration.matchPathRuleToCached("/api/users"));
    }
}
