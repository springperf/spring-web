package io.springperf.web.autoconfigure;

import io.netty.handler.ssl.SslContext;
import io.springperf.web.autoconfigure.actuator.ManagementServerInfrastructure;
import io.springperf.web.autoconfigure.actuator.PerfEndpointHandlerMapping;
import io.springperf.web.autoconfigure.actuator.PerfMappingDescriptionProvider;
import io.springperf.web.autoconfigure.actuator.server.ManagementNettyHttpServer;
import io.springperf.web.autoconfigure.actuator.server.SslContextFactory;
import io.springperf.web.context.WebContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.expose.IncludeExcludeEndpointFilter;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.CorsEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.endpoint.EndpointFilter;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.ParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.PathMapper;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpointDiscoverer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.stream.Collectors;

/**
 * Perf 框架的 Actuator 端点自动配置。
 * <p>当 classpath 中存在 {@link ExposableWebEndpoint}（即引入了 spring-boot-actuator）时自动生效。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ExposableWebEndpoint.class)
@EnableConfigurationProperties({WebEndpointProperties.class, CorsEndpointProperties.class})
public class PerfEndpointAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public PerfMappingDescriptionProvider perfMappingDescriptionProvider(WebContext webContext) {
        return new PerfMappingDescriptionProvider(webContext);
    }

    @Bean @ConditionalOnMissingBean
    public EndpointMediaTypes endpointMediaTypes() { return EndpointMediaTypes.DEFAULT; }

    @Bean @ConditionalOnMissingBean
    public WebEndpointDiscoverer webEndpointDiscoverer(
            ApplicationContext applicationContext, ParameterValueMapper parameterValueMapper,
            EndpointMediaTypes endpointMediaTypes, ObjectProvider<PathMapper> pathMappers,
            ObjectProvider<OperationInvokerAdvisor> invokerAdvisors,
            ObjectProvider<EndpointFilter<ExposableWebEndpoint>> filters) {
        return new WebEndpointDiscoverer(applicationContext, parameterValueMapper, endpointMediaTypes,
                pathMappers.orderedStream().collect(Collectors.toList()),
                invokerAdvisors.orderedStream().collect(Collectors.toList()),
                filters.orderedStream().collect(Collectors.toList()));
    }

    @Bean @ConditionalOnMissingBean
    public IncludeExcludeEndpointFilter<ExposableWebEndpoint> webExposeExcludePropertyEndpointFilter(Environment environment) {
        return new IncludeExcludeEndpointFilter<>(ExposableWebEndpoint.class, environment, "management.endpoints.web.exposure", "info");
    }

    @Bean @ConditionalOnMissingBean
    public PerfEndpointHandlerMapping perfEndpointHandlerMapping(
            WebEndpointsSupplier endpointsSupplier, EndpointMediaTypes endpointMediaTypes,
            WebEndpointProperties webEndpointProperties, CorsEndpointProperties corsEndpointProperties,
            WebContext webContext, ObjectProvider<ManagementServerInfrastructure> managementServerInfrastructureProvider) {

        ManagementServerInfrastructure infrastructure = managementServerInfrastructureProvider.getIfAvailable();
        return new PerfEndpointHandlerMapping(endpointsSupplier, endpointMediaTypes, webEndpointProperties,
                webContext, corsEndpointProperties, infrastructure);
    }

    @Bean(destroyMethod = "")
    @ConditionalOnProperty(value = "management.server.port", matchIfMissing = false)
    public ManagementServerInfrastructure managementServerInfrastructure(WebContext webContext,
                                                                         WebEndpointProperties webEndpointProperties,
                                                                         Environment environment) {
        int mgmtPort = environment.getProperty("management.server.port", int.class, 0);
        int mainPort = environment.getProperty("server.port", int.class, 8080);
        if (mgmtPort == mainPort) throw new IllegalStateException(
                "management.server.port (" + mgmtPort + ") must be different from server.port (" + mainPort + ")");
        String basePath = webEndpointProperties.getBasePath();
        return new ManagementServerInfrastructure(webContext, basePath);
    }

    @Bean(destroyMethod = "")
    @ConditionalOnProperty(value = "management.server.port", matchIfMissing = false)
    public ManagementNettyHttpServer managementNettyHttpServer(
            WebContext webContext, ManagementServerInfrastructure managementServerInfrastructure,
            WebEndpointProperties webEndpointProperties, Environment environment) {
        int mgmtPort = environment.getProperty("management.server.port", int.class, 0);
        SslContext sslContext = SslContextFactory.createServerSslContext(environment, "management.server.ssl.");
        return new ManagementNettyHttpServer(webContext, webEndpointProperties.getBasePath(),
                managementServerInfrastructure.getDispatcherHandler(), mgmtPort,
                environment.getProperty("server.http.max-content-length", int.class, 1048576), sslContext);
    }
}