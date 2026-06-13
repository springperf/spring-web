package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.CorsEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.endpoint.web.*;
import org.springframework.lang.Nullable;
import org.springframework.web.cors.CorsConfiguration;

import java.util.Collection;

/**
 * Perf 框架的 Actuator 端点处理器映射。
 * <p>作为 WebComponent 参与 WebContext 生命周期：Phase 1 注册 Actuator 路由，
 * Phase 2 触发 Management 路由优化器。</p>
 */
@Slf4j
public class ActuatorEndpointHandlerMapping extends BaseWebComponent {

    private final WebEndpointsSupplier endpointsSupplier;
    private final EndpointMediaTypes endpointMediaTypes;
    private final WebEndpointProperties properties;
    @Nullable private final CorsEndpointProperties corsEndpointProperties;
    @Nullable private final ManagementServerInfrastructure managementServerInfrastructure;

    public ActuatorEndpointHandlerMapping(WebEndpointsSupplier endpointsSupplier, EndpointMediaTypes endpointMediaTypes,
                                          WebEndpointProperties properties,
                                          @Nullable CorsEndpointProperties corsEndpointProperties,
                                          @Nullable ManagementServerInfrastructure managementServerInfrastructure) {
        this.endpointsSupplier = endpointsSupplier;
        this.endpointMediaTypes = endpointMediaTypes;
        this.properties = properties;
        this.corsEndpointProperties = corsEndpointProperties;
        this.managementServerInfrastructure = managementServerInfrastructure;
    }

    @Override
    public void initComponentPhase1() throws Exception {
        MappingRegistry mappingRegistry = getWebContext().getWebComponent(MappingRegistry.class);
        if (mappingRegistry == null) { log.warn("MappingRegistry not available, Actuator endpoints will not be registered"); return; }
        String basePath = properties.getBasePath();
        registerCorsConfiguration(basePath);
        WebServerNamespace serverNamespace = managementServerInfrastructure != null
                ? WebServerNamespace.MANAGEMENT : WebServerNamespace.SERVER;
        EndpointMapping endpointMapping = new EndpointMapping(basePath);
        Collection<ExposableWebEndpoint> endpoints = endpointsSupplier.getEndpoints();
        EndpointLinksResolver linksResolver = new EndpointLinksResolver(endpoints, basePath);
        LinksOperationInvoker linksInvoker = new LinksOperationInvoker();
        ActuatorPathMappingContext linksCtx = new ActuatorPathMappingContext(
                linksInvoker, endpointMapping.createSubPath(""), linksResolver, endpointMediaTypes, basePath,
                serverNamespace);
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
                ActuatorPathMappingContext mappingContext = new ActuatorPathMappingContext(
                        invoker, fullPath, operation, predicate, linksResolver, endpointMediaTypes, basePath,
                        serverNamespace);
                registerRoute(mappingContext, mappingRegistry);
            }
        }
        log.info("Registered {} Actuator endpoints (managementPort={})", endpoints.size(), managementServerInfrastructure != null);
    }

    @Override
    public void initComponentPhase2() throws Exception {
        super.initComponentPhase2();
        if (managementServerInfrastructure != null) {
            managementServerInfrastructure.getDispatcherHandler().buildOptimizerPipeline();
        }
    }

    private void registerRoute(PathMappingContext ctx, MappingRegistry mappingRegistry) {
        if (managementServerInfrastructure != null) {
            managementServerInfrastructure.getMappingRegistry().registerMapping(ctx);
            log.debug("Registered actuator route on management port: {}", ctx.getPathRule());
        } else {
            mappingRegistry.registerMapping(ctx);
        }
    }

    private void registerCorsConfiguration(String basePath) {
        if (corsEndpointProperties == null) {
            return;
        }
        CorsConfiguration corsConfig = corsEndpointProperties.toCorsConfiguration();
        if (corsConfig == null || corsConfig.getAllowedOrigins() == null) {
            return;
        }
        CorsRegistry corsRegistry = getWebContext().getWebComponent(CorsRegistry.class);
        if (corsRegistry == null) {
            log.debug("CorsRegistry not available, skipping Actuator CORS configuration");
            return;
        }
        corsRegistry.addActuatorCorsConfiguration(basePath + "/**", corsConfig);
    }
}