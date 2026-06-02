package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.CorsEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.endpoint.web.*;
import org.springframework.lang.Nullable;
import org.springframework.web.cors.CorsConfiguration;

import java.util.Collection;

/**
 * Perf 框架的 Actuator 端点处理器映射。
 */
@Slf4j
public class PerfEndpointHandlerMapping implements InitializingBean {

    private final WebEndpointsSupplier endpointsSupplier;
    private final EndpointMediaTypes endpointMediaTypes;
    private final WebEndpointProperties properties;
    private final WebContext webContext;
    @Nullable private final CorsEndpointProperties corsEndpointProperties;
    @Nullable private final ManagementServerInfrastructure managementServerInfrastructure;

    public PerfEndpointHandlerMapping(WebEndpointsSupplier endpointsSupplier, EndpointMediaTypes endpointMediaTypes,
                                       WebEndpointProperties properties, WebContext webContext,
                                       @Nullable CorsEndpointProperties corsEndpointProperties,
                                       @Nullable ManagementServerInfrastructure managementServerInfrastructure) {
        this.endpointsSupplier = endpointsSupplier;
        this.endpointMediaTypes = endpointMediaTypes;
        this.properties = properties;
        this.webContext = webContext;
        this.corsEndpointProperties = corsEndpointProperties;
        this.managementServerInfrastructure = managementServerInfrastructure;
    }

    @Override
    public void afterPropertiesSet() {
        MappingRegistry mappingRegistry = webContext.getWebComponent(MappingRegistry.class);
        if (mappingRegistry == null) { log.warn("MappingRegistry not available, Actuator endpoints will not be registered"); return; }
        String basePath = properties.getBasePath();
        registerCorsConfiguration(basePath);
        EndpointMapping endpointMapping = new EndpointMapping(basePath);
        Collection<ExposableWebEndpoint> endpoints = endpointsSupplier.getEndpoints();
        EndpointLinksResolver linksResolver = new EndpointLinksResolver(endpoints, basePath);
        LinksOperationInvoker linksInvoker = new LinksOperationInvoker();
        PerfActuatorPathMappingContext linksCtx = new PerfActuatorPathMappingContext(
                linksInvoker, endpointMapping.createSubPath(""), linksResolver, endpointMediaTypes, basePath);
        registerRoute(linksCtx, mappingRegistry);
        if (endpoints.isEmpty()) { log.info("No Actuator endpoints discovered"); return; }
        for (ExposableWebEndpoint endpoint : endpoints) {
            String rootPath = endpoint.getRootPath();
            String mappedRootPath = properties.getPathMapping().getOrDefault(rootPath, rootPath);
            for (WebOperation operation : endpoint.getOperations()) {
                WebOperationRequestPredicate predicate = operation.getRequestPredicate();
                // 应用 path-mapping：将 predicate 路径中的 rootPath 替换为 mappedRootPath
                String predicatePath = predicate.getPath().replace("/" + rootPath, "/" + mappedRootPath);
                String fullPath = endpointMapping.createSubPath(predicatePath);
                OperationHandlerInvoker invoker = new OperationHandlerInvoker(operation, predicate, endpointMediaTypes.getConsumed());
                PerfActuatorPathMappingContext mappingContext = new PerfActuatorPathMappingContext(
                        invoker, fullPath, operation, predicate, linksResolver, endpointMediaTypes, basePath);
                registerRoute(mappingContext, mappingRegistry);
            }
        }
        log.info("Registered {} Actuator endpoints (managementPort={})", endpoints.size(), managementServerInfrastructure != null);
        if (managementServerInfrastructure != null) managementServerInfrastructure.getDispatcherHandler().buildOptimizerPipeline();
    }

    private void registerRoute(PathMappingContext ctx, MappingRegistry mappingRegistry) {
        if (managementServerInfrastructure != null) managementServerInfrastructure.getDispatcherHandler().registerRoute(ctx);
        else mappingRegistry.registerMappingAfterInit(ctx);
    }

    private void registerCorsConfiguration(String basePath) {
        if (corsEndpointProperties == null) return;
        CorsConfiguration corsConfig = corsEndpointProperties.toCorsConfiguration();
        if (corsConfig == null || corsConfig.getAllowedOrigins() == null) return;
        CorsRegistry corsRegistry = webContext.getWebComponent(CorsRegistry.class);
        if (corsRegistry == null) { log.debug("CorsRegistry not available, skipping Actuator CORS configuration"); return; }
        corsRegistry.addActuatorCorsConfiguration(basePath + "/**", corsConfig);
    }
}