package io.springperf.web.support.codec.interceptor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SupportHttpBodyCodecInterceptorRegistryTest {

    @Test
    void constructor_doesNotThrow() {
        assertDoesNotThrow(SupportHttpBodyCodecInterceptorRegistry::new);
    }
}