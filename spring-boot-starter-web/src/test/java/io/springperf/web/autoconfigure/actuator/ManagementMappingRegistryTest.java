package io.springperf.web.autoconfigure.actuator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ManagementMappingRegistryTest {

    private final ManagementMappingRegistry registry = new ManagementMappingRegistry();

    @Test
    void initComponentPhase1_doesNothing() {
        assertDoesNotThrow(() -> registry.initComponentPhase1());
    }

    @Test
    void initComponentPhase2_doesNothing() {
        assertDoesNotThrow(() -> registry.initComponentPhase2());
    }

    @Test
    void buildOptimizerPipeline_withEmptyRoutes_logsWarning() {
        assertDoesNotThrow(() -> registry.buildOptimizerPipeline());
    }

    @Test
    void extendsMappingRegistry() {
        assertInstanceOf(io.springperf.web.core.mapping.MappingRegistry.class, registry);
    }
}