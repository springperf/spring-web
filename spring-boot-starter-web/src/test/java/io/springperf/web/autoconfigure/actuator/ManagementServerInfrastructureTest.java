package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagementServerInfrastructureTest {

    @Mock WebContext webContext;

    @BeforeEach
    void setUp() {
        when(webContext.getWebComponentWithDefault(any(Class.class), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    void constructor_createsDispatcherHandler() {
        ManagementServerInfrastructure infrastructure = new ManagementServerInfrastructure(webContext, "/actuator");

        assertNotNull(infrastructure.getDispatcherHandler());
    }

    @Test
    void constructor_createsMappingRegistry() {
        ManagementServerInfrastructure infrastructure = new ManagementServerInfrastructure(webContext, "/actuator");

        assertNotNull(infrastructure.getMappingRegistry());
    }

    @Test
    void constructor_basePathWithTrailingSlash() {
        ManagementServerInfrastructure infrastructure = new ManagementServerInfrastructure(webContext, "/management/");

        assertNotNull(infrastructure.getDispatcherHandler());
    }
}