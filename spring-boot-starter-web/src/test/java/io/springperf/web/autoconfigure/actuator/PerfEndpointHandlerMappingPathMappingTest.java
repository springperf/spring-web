package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebOperation;
import org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate;
import org.springframework.boot.actuate.endpoint.web.WebEndpointHttpMethod;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfEndpointHandlerMappingPathMappingTest {

    @Mock private WebEndpointsSupplier endpointsSupplier;
    @Mock private ExposableWebEndpoint endpoint;
    @Mock private WebOperation operation;
    @Mock private WebContext webContext;

    private ManagementMappingRegistry registry;
    private WebEndpointProperties properties;

    private static final String BASE_PATH = "/actuator";

    @BeforeEach
    void setUp() {
        properties = new WebEndpointProperties();
        properties.setBasePath(BASE_PATH);

        MappingRegistry dummy = mock(MappingRegistry.class);
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(dummy);
    }

    @Test
    void pathMapping_applies_whenConfigured() {
        properties.getPathMapping().put("health", "healthcheck");
        setupSingleEndpoint("health", "/health");
        PerfEndpointHandlerMapping mapping = createMapping();

        mapping.afterPropertiesSet();

        List<String> opPaths = operationPaths(registry);
        assertTrue(opPaths.contains("/actuator/healthcheck"),
                "Should contain mapped path '/actuator/healthcheck'. Actual: " + opPaths);
        assertFalse(opPaths.contains("/actuator/health"),
                "Should NOT contain original path '/actuator/health'. Actual: " + opPaths);
    }

    @Test
    void pathMapping_usesOriginalPath_whenNotConfigured() {
        setupSingleEndpoint("health", "/health");
        PerfEndpointHandlerMapping mapping = createMapping();

        mapping.afterPropertiesSet();

        List<String> opPaths = operationPaths(registry);
        assertTrue(opPaths.contains("/actuator/health"),
                "Without path-mapping, should contain original path. Actual: " + opPaths);
    }

    @Test
    void pathMapping_worksForMultipleEndpoints() {
        properties.getPathMapping().put("health", "healthcheck");
        properties.getPathMapping().put("info", "app-info");

        // health endpoint
        WebOperationRequestPredicate healthPred = new WebOperationRequestPredicate(
                "/health", WebEndpointHttpMethod.GET,
                Collections.emptyList(), Collections.singletonList("application/json"));
        setupEndpoint("health", healthPred);

        // info endpoint
        ExposableWebEndpoint infoEndpoint = mock(ExposableWebEndpoint.class);
        WebOperation infoOp = mock(WebOperation.class);
        WebOperationRequestPredicate infoPred = new WebOperationRequestPredicate(
                "/info", WebEndpointHttpMethod.GET,
                Collections.emptyList(), Collections.singletonList("application/json"));
        when(infoEndpoint.getRootPath()).thenReturn("info");
        when(infoEndpoint.getOperations()).thenReturn(Collections.singletonList(infoOp));
        when(infoOp.getRequestPredicate()).thenReturn(infoPred);

        when(endpointsSupplier.getEndpoints()).thenReturn(Arrays.asList(endpoint, infoEndpoint));

        PerfEndpointHandlerMapping mapping = createMapping();
        mapping.afterPropertiesSet();

        List<String> opPaths = operationPaths(registry);
        assertTrue(opPaths.contains("/actuator/healthcheck"),
                "health should map to healthcheck. Actual: " + opPaths);
        assertTrue(opPaths.contains("/actuator/app-info"),
                "info should map to app-info. Actual: " + opPaths);
        assertFalse(opPaths.contains("/actuator/health"),
                "Should not contain '/actuator/health'. Actual: " + opPaths);
        assertFalse(opPaths.contains("/actuator/info"),
                "Should not contain '/actuator/info'. Actual: " + opPaths);
    }

    @Test
    void pathMapping_applies_whenRootPathInPredicateHasWildcard() {
        properties.getPathMapping().put("health", "healthcheck");
        setupSingleEndpoint("health", "/health/{**}");
        PerfEndpointHandlerMapping mapping = createMapping();

        mapping.afterPropertiesSet();

        List<String> opPaths = operationPaths(registry);
        assertTrue(opPaths.contains("/actuator/healthcheck/{**}"),
                "Wildcard path should be mapped. Actual: " + opPaths);
        assertFalse(opPaths.contains("/actuator/health/{**}"),
                "Original wildcard path should not exist. Actual: " + opPaths);
    }

    @Test
    void pathMapping_producesCorrectFullPath() {
        properties.getPathMapping().put("health", "healthcheck");
        setupSingleEndpoint("health", "/health");
        PerfEndpointHandlerMapping mapping = createMapping();

        mapping.afterPropertiesSet();

        List<String> allPaths = registry.getMappingContextList().stream()
                .map(PathMappingContext::getPathRule)
                .collect(Collectors.toList());
        assertTrue(allPaths.contains("/actuator/healthcheck"),
                "Expected '/actuator/healthcheck'. Actual: " + allPaths);
    }

    // ===== helper methods =====

    private void setupEndpoint(String rootPath, WebOperationRequestPredicate predicate) {
        lenient().when(endpoint.getRootPath()).thenReturn(rootPath);
        lenient().when(endpoint.getOperations()).thenReturn(Collections.singletonList(operation));
        lenient().when(operation.getRequestPredicate()).thenReturn(predicate);
    }

    private void setupSingleEndpoint(String rootPath, String predicatePath) {
        WebOperationRequestPredicate predicate = new WebOperationRequestPredicate(
                predicatePath, WebEndpointHttpMethod.GET,
                Collections.emptyList(), Collections.singletonList("application/json"));
        setupEndpoint(rootPath, predicate);
        when(endpointsSupplier.getEndpoints()).thenReturn(Collections.singletonList(endpoint));
    }

    private PerfEndpointHandlerMapping createMapping() {
        ManagementServerInfrastructure infrastructure = new ManagementServerInfrastructure(webContext, BASE_PATH);
        this.registry = infrastructure.getMappingRegistry();
        return new PerfEndpointHandlerMapping(
                endpointsSupplier, EndpointMediaTypes.DEFAULT, properties, webContext, null, infrastructure);
    }

    private static List<String> operationPaths(ManagementMappingRegistry reg) {
        return reg.getMappingContextList().stream()
                .map(PathMappingContext::getPathRule)
                .filter(p -> !p.equals(BASE_PATH))
                .collect(Collectors.toList());
    }
}