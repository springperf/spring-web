package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.CorsEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.endpoint.web.*;
import org.springframework.web.cors.CorsConfiguration;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManagementConfigPropertiesTest {

    @Mock private WebEndpointsSupplier endpointsSupplier;
    @Mock private ExposableWebEndpoint endpoint;
    @Mock private WebOperation operation;
    @Mock private WebContext webContext;

    private ManagementMappingRegistry registry;

    @BeforeEach
    void setUp() {
        MappingRegistry dummy = mock(MappingRegistry.class);
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(dummy);
        lenient().when(webContext.getWebComponentWithDefault(any(Class.class), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    void basePath_customValue_isUsedForRouteValidation() throws Exception {
        // Given: custom base-path "/management"
        WebEndpointProperties properties = new WebEndpointProperties();
        properties.setBasePath("/management");

        // When: 创建 infrastructure 并注册路由
        ManagementServerInfrastructure infrastructure = new ManagementServerInfrastructure(webContext, properties.getBasePath());
        this.registry = infrastructure.getMappingRegistry();

        WebOperationRequestPredicate predicate = new WebOperationRequestPredicate(
                "/health", WebEndpointHttpMethod.GET,
                Collections.emptyList(), Collections.singletonList("application/json"));
        setupEndpoint("health", predicate);
        when(endpointsSupplier.getEndpoints()).thenReturn(Collections.singletonList(endpoint));

        ActuatorEndpointHandlerMapping mapping = new ActuatorEndpointHandlerMapping(
                endpointsSupplier, EndpointMediaTypes.DEFAULT, properties, null, infrastructure);
        mapping.initWithWebContext(webContext);
        mapping.initComponentPhase1();

        // Then: 路由应注册在 /management/health 而非 /actuator/health
        List<String> paths = registry.getMappingContextList().stream()
                .map(PathMappingContext::getPathRule)
                .collect(Collectors.toList());

        assertTrue(paths.contains("/management/health"),
                "Route should use custom base-path '/management'. Actual: " + paths);
        assertFalse(paths.contains("/actuator/health"),
                "Route should NOT use default '/actuator'. Actual: " + paths);
    }

    @Test
    void corsConfiguration_isBoundAndPassedToCorsRegistry() throws Exception {
        // Given: simulated CorsRegistry
        CorsRegistry corsRegistry = mock(CorsRegistry.class);
        when(webContext.getWebComponent(CorsRegistry.class)).thenReturn(corsRegistry);

        // Given: configured CORS
        CorsEndpointProperties corsProps = new CorsEndpointProperties();
        corsProps.setAllowedOrigins(Collections.singletonList("http://example.com"));
        corsProps.setAllowedMethods(Collections.singletonList("GET"));

        WebEndpointProperties properties = new WebEndpointProperties();
        properties.setBasePath("/actuator");

        // Given: 无 managementHttpHandler → 走主端口路径测试 CORS 注册
        setupEndpoint("health", new WebOperationRequestPredicate(
                "/health", WebEndpointHttpMethod.GET,
                Collections.emptyList(), Collections.singletonList("application/json")));
        when(endpointsSupplier.getEndpoints()).thenReturn(Collections.singletonList(endpoint));

        ActuatorEndpointHandlerMapping mapping = new ActuatorEndpointHandlerMapping(
                endpointsSupplier, EndpointMediaTypes.DEFAULT, properties, corsProps, null);
        mapping.initWithWebContext(webContext);
        mapping.initComponentPhase1();

        // Then: CorsRegistry.addActuatorCorsConfiguration 被调用
        verify(corsRegistry, times(1))
                .addActuatorCorsConfiguration(eq("/actuator/**"), any(CorsConfiguration.class));
    }

    @Test
    void basePath_defaultIsActuator() throws Exception {
        // Given: default base-path (not set)
        WebEndpointProperties properties = new WebEndpointProperties();

        ManagementServerInfrastructure infrastructure = new ManagementServerInfrastructure(webContext, properties.getBasePath());
        this.registry = infrastructure.getMappingRegistry();

        WebOperationRequestPredicate predicate = new WebOperationRequestPredicate(
                "/health", WebEndpointHttpMethod.GET,
                Collections.emptyList(), Collections.singletonList("application/json"));
        setupEndpoint("health", predicate);
        when(endpointsSupplier.getEndpoints()).thenReturn(Collections.singletonList(endpoint));

        ActuatorEndpointHandlerMapping mapping = new ActuatorEndpointHandlerMapping(
                endpointsSupplier, EndpointMediaTypes.DEFAULT, properties, null, infrastructure);
        mapping.initWithWebContext(webContext);
        mapping.initComponentPhase1();

        // Then: 默认路由为 /actuator/health
        List<String> paths = registry.getMappingContextList().stream()
                .map(PathMappingContext::getPathRule)
                .collect(Collectors.toList());

        assertTrue(paths.contains("/actuator/health"),
                "Default base-path should be '/actuator'. Actual: " + paths);
    }

    private void setupEndpoint(String rootPath, WebOperationRequestPredicate predicate) {
        lenient().when(endpoint.getRootPath()).thenReturn(rootPath);
        lenient().when(endpoint.getOperations()).thenReturn(Collections.singletonList(operation));
        lenient().when(operation.getRequestPredicate()).thenReturn(predicate);
    }
}