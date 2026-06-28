package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.openapi.OpenApiAdapter;
import io.springperf.web.context.WebContext;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Perf 框架的 SpringDoc OpenAPI 自动配置。
 *
 * <p>当 classpath 中存在 {@link OpenApiCustomizer}（即引入了 springdoc-openapi 依赖）时自动生效。
 * 将框架 {@link io.springperf.web.core.mapping.MappingRegistry} 中的路由暴露到 OpenAPI 文档，
 * 使用户无需手动编写适配器即可在 Swagger UI 中看到所有端点。</p>
 *
 * <p>使用方式：在项目中添加 springdoc-openapi-ui 依赖即可。</p>
 *
 * @author huangcanda
 * @since 1.0.4
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OpenApiCustomizer.class)
public class OpenApiAutoConfiguration {

    @Bean
    public OpenApiCustomizer springWebOpenApiCustomizer(WebContext webContext) {
        OpenApiAdapter adapter = new OpenApiAdapter(webContext);
        return openApi -> adapter.customize(openApi);
    }
}