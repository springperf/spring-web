package org.springframework.web.servlet.config.annotation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceHandlerRegistryTest {

    private final ResourceHandlerRegistry registry = new ResourceHandlerRegistry();

    @Test
    void addResourceHandler_createsRegistration() {
        ResourceHandlerRegistration reg = registry.addResourceHandler("/static/**");
        assertNotNull(reg);
        assertArrayEquals(new String[]{"/static/**"}, reg.getPathPatterns());
    }

    @Test
    void addResourceHandler_multipleHandlers() {
        ResourceHandlerRegistration reg1 = registry.addResourceHandler("/img/**");
        ResourceHandlerRegistration reg2 = registry.addResourceHandler("/css/**");

        assertEquals(2, registry.getRegistrations().size());
        assertNotSame(reg1, reg2);
    }

    @Test
    void getRegistrations_initialState_returnsEmpty() {
        assertTrue(registry.getRegistrations().isEmpty());
    }

    @Test
    void addResourceHandler_chainedConfig_persists() {
        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(3600);

        ResourceHandlerRegistration reg = registry.getRegistrations().get(0);
        assertEquals(1, reg.getLocationValues().size());
        assertEquals(Integer.valueOf(3600), reg.getCachePeriod());
    }
}