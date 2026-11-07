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
    void allowedOrigins_replacesDefaultsEntirely() {
        CorsRegistration reg = new CorsRegistration("/api/**").allowedOrigins("https://example.com");
        // Spring 语义是"替换"默认值：contains 断言会漏掉"默认 * 仍残留"（安全配置被放宽）
        assertEquals(java.util.List.of("https://example.com"), reg.getCorsConfiguration().getAllowedOrigins());
    }

    @Test
    void allowedMethods_replacesDefaultsEntirely() {
        CorsRegistration reg = new CorsRegistration("/api/**").allowedMethods("GET", "POST");
        assertEquals(java.util.List.of("GET", "POST"), reg.getCorsConfiguration().getAllowedMethods());
    }

    @Test
    void allowedHeaders_replacesDefaultsEntirely() {
        CorsRegistration reg = new CorsRegistration("/api/**").allowedHeaders("X-Custom");
        assertEquals(java.util.List.of("X-Custom"), reg.getCorsConfiguration().getAllowedHeaders());
    }

    @Test
    void exposedHeaders_setsHeaders() {
        CorsRegistration reg = new CorsRegistration("/api/**").exposedHeaders("X-Result");
        assertEquals(java.util.List.of("X-Result"), reg.getCorsConfiguration().getExposedHeaders());
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
        CorsRegistration reg = new CorsRegistration("/api/**");
        // 方法名承诺 returnsSelf，就必须 assertSame（断言非空无法证明链式返回的是自身）
        assertAll(() -> assertSame(reg, reg.allowedOrigins("*")),
                () -> assertSame(reg, reg.allowedMethods("GET")),
                () -> assertSame(reg, reg.allowedHeaders("X-Hdr")),
                () -> assertSame(reg, reg.exposedHeaders("X-Res")),
                () -> assertSame(reg, reg.allowCredentials(false)), () -> assertSame(reg, reg.maxAge(100)));
        assertEquals("/api/**", reg.getPathPattern());
    }
}
