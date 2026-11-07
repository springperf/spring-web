package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.openapi.OpenApiDocController;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.resource.ResourceHandlerRegistration;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 用真实条件求值验证 OpenAPI / Swagger UI 开关，而不是只反射检查注解是否存在。
 * <p>
 * 不加载 NettyHttpServer，故不会启动端口；WebContext 以 mock 注入即可满足 OpenApiAdapter 构造。
 * </p>
 */
class OpenApiSwitchConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiAutoConfiguration.class, SwaggerUiAutoConfiguration.class))
            .withBean(WebContext.class, () -> mock(WebContext.class));

    @Test
    void defaults_registerWholeIntegration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpenApiCustomizer.class);
            assertThat(context).hasSingleBean(OpenApiDocController.class);
            assertThat(context).hasBean("swaggerUiResourceHandler");
        });
    }

    @Test
    void openApiDisabled_backsOffWholeIntegration() {
        runner.withPropertyValues("springperf.openapi.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OpenApiCustomizer.class);
            assertThat(context).doesNotHaveBean(OpenApiDocController.class);
            assertThat(context).doesNotHaveBean("swaggerUiResourceHandler");
        });
    }

    @Test
    void swaggerUiDisabled_keepsDocumentEndpoints() {
        runner.withPropertyValues("springperf.swagger-ui.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpenApiCustomizer.class);
            assertThat(context).hasSingleBean(OpenApiDocController.class);
            assertThat(context).doesNotHaveBean("swaggerUiResourceHandler");
        });
    }

    @Test
    void userDefinedResourceHandler_takesPrecedence() {
        runner.withBean("swaggerUiResourceHandler", ResourceHandlerRegistration.class,
                () -> new ResourceHandlerRegistration("/custom/**")).run(context -> {
                    assertThat(context).hasNotFailed();
                    ResourceHandlerRegistration registration = context.getBean("swaggerUiResourceHandler",
                            ResourceHandlerRegistration.class);
                    assertThat(registration.getPathPatterns()).containsExactly("/custom/**");
                });
    }
}
