package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.actuator.ActuatorMappingDescriptionProvider;
import io.springperf.web.autoconfigure.actuator.ManagementServerInfrastructure;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.autoconfigure.endpoint.expose.IncludeExcludeEndpointFilter;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ActuatorEndpointAutoConfigurationTest {

    private ActuatorEndpointAutoConfiguration config = new ActuatorEndpointAutoConfiguration();

    @Mock WebContext webContext;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        environment.setProperty("server.port", "8080");
        lenient().when(webContext.getWebComponentWithDefault(any(Class.class), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    void configuration_hasConditionalOnClass() {
        ConditionalOnClass annotation = ActuatorEndpointAutoConfiguration.class.getAnnotation(ConditionalOnClass.class);
        assertNotNull(annotation);
        assertEquals(2, annotation.value().length);
        assertEquals(ExposableWebEndpoint.class, annotation.value()[0]);
        assertEquals(ApiVersion.class, annotation.value()[1]);
    }

    @Test
    void configuration_hasEnableConfigurationProperties() {
        EnableConfigurationProperties annotation = ActuatorEndpointAutoConfiguration.class
                .getAnnotation(EnableConfigurationProperties.class);
        assertNotNull(annotation);
    }

    @Test
    void perfMappingDescriptionProvider_hasConditionalOnMissingBean() throws Exception {
        Method method = ActuatorEndpointAutoConfiguration.class.getMethod("perfMappingDescriptionProvider", WebContext.class);
        assertNotNull(method.getAnnotation(ConditionalOnMissingBean.class));
    }

    @Test
    void perfMappingDescriptionProvider_createsBean() {
        ActuatorMappingDescriptionProvider bean = config.perfMappingDescriptionProvider(webContext);
        assertNotNull(bean);
        assertInstanceOf(ActuatorMappingDescriptionProvider.class, bean);
    }

    @Test
    void endpointMediaTypes_hasConditionalOnMissingBean() throws Exception {
        Method method = ActuatorEndpointAutoConfiguration.class.getMethod("endpointMediaTypes");
        assertNotNull(method.getAnnotation(ConditionalOnMissingBean.class));
    }

    @Test
    void endpointMediaTypes_returnsDefault() {
        EndpointMediaTypes bean = config.endpointMediaTypes();
        assertNotNull(bean);
        assertSame(EndpointMediaTypes.DEFAULT, bean);
    }

    @Test
    void perfExposeExcludePropertyEndpointFilter_createsBean() {
        IncludeExcludeEndpointFilter<?> bean = config.perfExposeExcludePropertyEndpointFilter(environment);
        assertNotNull(bean);
    }

    @Test
    void managementServerInfrastructure_hasConditionalOnProperty() throws Exception {
        Method method = ActuatorEndpointAutoConfiguration.class.getMethod("managementServerInfrastructure",
                WebContext.class, WebEndpointProperties.class, Environment.class);
        ConditionalOnProperty annotation = method.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(annotation);
        assertEquals("management.server.port", annotation.value()[0]);
    }

    @Test
    void managementServerInfrastructure_differentPort_createsInfrastructure() {
        environment.setProperty("management.server.port", "9090");

        WebEndpointProperties props = new WebEndpointProperties();
        ManagementServerInfrastructure bean = config.managementServerInfrastructure(webContext, props, environment);

        assertNotNull(bean);
        assertNotNull(bean.getDispatcherHandler());
        assertNotNull(bean.getMappingRegistry());
    }

    @Test
    void managementServerInfrastructure_samePort_throwsIllegalStateException() {
        environment.setProperty("management.server.port", "8080");

        WebEndpointProperties props = new WebEndpointProperties();
        assertThrows(IllegalStateException.class,
                () -> config.managementServerInfrastructure(webContext, props, environment));
    }

    @Test
    void managementServerInfrastructure_zeroPortIsDifferent_createsInfrastructure() {
        // management.server.port not set, defaults to 0
        WebEndpointProperties props = new WebEndpointProperties();
        ManagementServerInfrastructure bean = config.managementServerInfrastructure(webContext, props, environment);

        assertNotNull(bean);
    }
}