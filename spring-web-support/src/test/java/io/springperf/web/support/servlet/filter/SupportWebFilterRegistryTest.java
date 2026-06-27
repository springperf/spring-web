package io.springperf.web.support.servlet.filter;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.filter.WebFilterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class SupportWebFilterRegistryTest {

    @Test
    void constructor_doesNotThrow() {
        assertDoesNotThrow(() -> new SupportWebFilterRegistry(mock(DispatcherHandler.class)));
    }

    @Test
    void constructor_extendsWebFilterRegistry() {
        assertInstanceOf(WebFilterRegistry.class, new SupportWebFilterRegistry(mock(DispatcherHandler.class)));
    }
}
