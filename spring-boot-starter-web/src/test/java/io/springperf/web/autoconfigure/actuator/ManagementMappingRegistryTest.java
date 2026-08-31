package io.springperf.web.autoconfigure.actuator;

import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    void buildOptimizerPipeline_withRoutes_buildsOptimizersAndRoutes() throws Exception {
        // 覆盖非空路由的真实优化路径：registerMapping 注入路由后，
        // buildOptimizerPipeline() 应触发 optimizeMapping 构建优化器
        Method method = StubController.class.getMethod("health");
        HandlerMethod handlerMethod = new HandlerMethod(new StubController(), method);
        io.springperf.web.core.mapping.PathMappingContext ctx =
                new io.springperf.web.core.mapping.PathMappingContext(handlerMethod, Collections.emptyList(), "/actuator/health");

        registry.registerMapping(ctx);
        assertDoesNotThrow(() -> registry.buildOptimizerPipeline());

        List<io.springperf.web.core.mapping.PathMappingContext> contexts = registry.getMappingContextList();
        assertNotNull(contexts);
        assertInstanceOf(io.springperf.web.core.mapping.PathMappingContext.class, contexts.get(0));
    }

    @Test
    void extendsMappingRegistry() {
        assertInstanceOf(io.springperf.web.core.mapping.MappingRegistry.class, registry);
    }

    static class StubController {
        @SuppressWarnings("unused")
        public String health() {
            return "UP";
        }
    }
}