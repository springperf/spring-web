package org.springframework.web.servlet.config.annotation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CorsRegistryTest {

    private final CorsRegistry registry = new CorsRegistry();

    @Test
    void addMapping_createsRegistration() {
        CorsRegistration reg = registry.addMapping("/api/**");
        assertNotNull(reg);
        assertEquals("/api/**", reg.getPathPattern());
    }

    @Test
    void addMapping_multipleMappings_returnsDifferentRegistrations() {
        CorsRegistration reg1 = registry.addMapping("/api/**");
        CorsRegistration reg2 = registry.addMapping("/admin/**");

        assertEquals(2, registry.getRegistrations().size());
        assertNotSame(reg1, reg2);
    }

    @Test
    void getRegistrations_initialState_returnsEmpty() {
        assertTrue(registry.getRegistrations().isEmpty());
    }

    @Test
    void addMapping_configuredRegistration_persistsConfig() {
        CorsRegistration reg = registry.addMapping("/api/**").allowedOrigins("https://example.com")
                .allowedMethods("GET");

        assertEquals(1, registry.getRegistrations().size());
        assertSame(reg, registry.getRegistrations().get(0));
        // 方法名承诺 persistsConfig：链式调用后的配置必须仍在同一注册项上
        assertEquals(java.util.List.of("https://example.com"), reg.getCorsConfiguration().getAllowedOrigins());
        assertEquals(java.util.List.of("GET"), reg.getCorsConfiguration().getAllowedMethods());
    }
}
