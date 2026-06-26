package io.springperf.web.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LifecycleWebComponentTest {

    static class TestLifecycleComponent implements LifecycleWebComponent {
    }

    @Test
    void defaultMethods_doNotThrow() throws Exception {
        LifecycleWebComponent component = new TestLifecycleComponent();
        component.initComponentPhase1();
        component.initComponentPhase2();
        component.initComponentPhase3();
        component.destroyComponent();
        // should not throw
    }

    @Test
    void getComponentName_returnsSimpleClassName() {
        LifecycleWebComponent component = new TestLifecycleComponent();
        assertEquals("TestLifecycleComponent", component.getComponentName());
    }
}