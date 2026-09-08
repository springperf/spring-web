package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.openapi.OpenApiDocController;
import io.springperf.web.core.resource.ResourceHandlerRegistration;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomiser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SwaggerUiAutoConfigurationTest {

    private final SwaggerUiAutoConfiguration config = new SwaggerUiAutoConfiguration();

    @Test
    void openApiDocController_createsBean() {
        OpenApiDocController controller = config.openApiDocController(
                mock(OpenApiCustomiser.class), new OpenApiProperties());
        assertNotNull(controller);
    }

    @Test
    void openApiDocController_returnsNewInstanceEachCall() {
        assertNotSame(config.openApiDocController(mock(OpenApiCustomiser.class), new OpenApiProperties()),
                config.openApiDocController(mock(OpenApiCustomiser.class), new OpenApiProperties()));
    }

    @Test
    void swaggerUiResourceHandler_usesWebjarVersion() {
        SwaggerUiProperties swaggerProps = new SwaggerUiProperties();
        swaggerProps.setWebjarVersion("5.4.0");

        ResourceHandlerRegistration reg = config.swaggerUiResourceHandler(swaggerProps);

        assertNotNull(reg);
        assertArrayEquals(new String[]{"/swagger-ui/**"}, reg.getPathPatterns());
        assertEquals(1, reg.getLocationValues().size());
        assertEquals("classpath:/META-INF/resources/webjars/swagger-ui/5.4.0",
                reg.getLocationValues().get(0), "addResourceLocations 浼氬幓鎺夊熬閮ㄦ枩鏉?);
    }

    @Test
    void swaggerUiResourceHandler_usesDefaultVersion() {
        ResourceHandlerRegistration reg = config.swaggerUiResourceHandler(new SwaggerUiProperties());
        assertEquals("classpath:/META-INF/resources/webjars/swagger-ui/5.2.0",
                reg.getLocationValues().get(0));
    }
}