package io.springperf.web.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LifecycleWebComponentTest {

    static class TestLifecycleComponent implements LifecycleWebComponent {
    }

    @Test
    void getComponentName_returnsSimpleClassName() {
        LifecycleWebComponent component = new TestLifecycleComponent();
        assertEquals("TestLifecycleComponent", component.getComponentName());
    }
}