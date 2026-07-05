package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.openapi.OpenApiDocController;
import io.springperf.web.core.resource.ResourceHandlerRegistration;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for Swagger UI endpoints and static resources.
 *
 * <p>When {@code springdoc-openapi-common} and {@code swagger-ui} webjar are on the classpath,
 * this configuration provides:
 * <ul>
 *   <li>{@link OpenApiDocController} — {@code /v3/api-docs}, {@code /v3/api-docs/swagger-config},
 *       and {@code /swagger-ui.html} redirect endpoints</li>
 *   <li>{@link ResourceHandlerRegistration} — serves Swagger UI static resources from
 *       the webjar under {@code /swagger-ui/**}</li>
 * </ul>
 *
 * <p>Users can override individual beans, e.g. define their own {@code OpenApiDocController}
 * or a custom {@code ResourceHandlerRegistration} named {@code swaggerUiResourceHandler}.</p>
 *
 * @see OpenApiAutoConfiguration
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OpenApiCustomizer.class)
@EnableConfigurationProperties({SwaggerUiProperties.class, OpenApiProperties.class})
public class SwaggerUiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenApiDocController openApiDocController(OpenApiCustomizer openApiCustomiser,
                                                     OpenApiProperties openApiProperties) {
        return new OpenApiDocController(openApiCustomiser, openApiProperties);
    }

    @Bean
    public ResourceHandlerRegistration swaggerUiResourceHandler(SwaggerUiProperties swaggerUiProperties) {
        return new ResourceHandlerRegistration("/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/swagger-ui/"
                        + swaggerUiProperties.getWebjarVersion() + "/");
    }
}