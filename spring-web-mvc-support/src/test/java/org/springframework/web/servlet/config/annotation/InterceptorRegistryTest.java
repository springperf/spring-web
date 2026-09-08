package org.springframework.web.servlet.config.annotation;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.HandlerInterceptor;

import static org.junit.jupiter.api.Assertions.*;

class InterceptorRegistryTest {

    private final InterceptorRegistry registry = new InterceptorRegistry();

    @Test
    void addInterceptor_createsRegistration() {
        HandlerInterceptor interceptor = new HandlerInterceptor() {};
        InterceptorRegistration reg = registry.addInterceptor(interceptor);

        assertNotNull(reg);
        assertSame(interceptor, reg.getInterceptor());
    }

    @Test
    void addInterceptor_multipleInterceptors_returnsDifferentRegistrations() {
        HandlerInterceptor i1 = new HandlerInterceptor() {};
        HandlerInterceptor i2 = new HandlerInterceptor() {};

        InterceptorRegistration reg1 = registry.addInterceptor(i1);
        InterceptorRegistration reg2 = registry.addInterceptor(i2);

        assertEquals(2, registry.getRegistrations().size());
        assertNotSame(reg1, reg2);
    }

    @Test
    void getRegistrations_initialState_returnsEmpty() {
        assertTrue(registry.getRegistrations().isEmpty());
    }

    @Test
    void addInterceptor_chainedConfig_persists() {
        HandlerInterceptor interceptor = new HandlerInterceptor() {};
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/**")
                .order(2);

        InterceptorRegistration reg = registry.getRegistrations().get(0);
        assertEquals(1, reg.getIncludePatterns().size());
        assertEquals(2, reg.getOrder());
    }
}