package io.springperf.web.context;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import static org.junit.jupiter.api.Assertions.*;

class WebComponentTest {

    static class TestComponent implements WebComponent {
    }

    @Test
    void getComponentName_returnsSimpleClassName() {
        WebComponent component = new TestComponent();
        assertEquals("TestComponent", component.getComponentName());
    }

    @Test
    void getOrder_returnsDefaultPriority() {
        WebComponent component = new TestComponent();
        assertEquals(Ordered.LOWEST_PRECEDENCE - 10000, component.getOrder());
    }
}