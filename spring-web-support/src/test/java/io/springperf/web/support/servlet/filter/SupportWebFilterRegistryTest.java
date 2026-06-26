package io.springperf.web.support.servlet.filter;

import io.springperf.web.core.filter.WebFilterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SupportWebFilterRegistryTest {

    @Test
    void constructor_doesNotThrow() {
        assertDoesNotThrow(SupportWebFilterRegistry::new);
    }

    @Test
    void constructor_extendsWebFilterRegistry() {
        assertInstanceOf(WebFilterRegistry.class, new SupportWebFilterRegistry());
    }
}
