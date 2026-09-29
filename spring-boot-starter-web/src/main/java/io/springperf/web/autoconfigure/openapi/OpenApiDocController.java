package io.springperf.web.autoconfigure.openapi;

import io.springperf.web.autoconfigure.OpenApiProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Auto-configured OpenAPI / Swagger UI endpoints.
 * <p>
 * Activated when {@link OpenApiCustomizer} (springdoc-openapi-common) is on the classpath. Users can override by
 * defining their own {@code OpenApiDocController} bean.
 * </p>
 */
@RestController
public class OpenApiDocController {

    private final OpenApiCustomizer openApiCustomiser;
    private final OpenApiProperties openApiProperties;

    /**
     * 文档缓存。路由表在启动期构建后运行期不变，故文档内容恒定，可安全缓存。
     * <p>
     * 缓存的意义不只是省掉重复构建：{@code OpenApiAdapter} 会为每个路由反射展开请求/响应 POJO 的属性，
     * 单次生成与路由数成正比（实测 500 路由约 74 µs 且分配数千个 Schema 对象）。而本端点是无鉴权的静态
     * 资源式接口，若不缓存，被轮询或误暴露到生产时会持续消耗 CPU 与 GC。
     * </p>
     * <p>
     * 用 volatile + 双重检查：{@code apiDocs()} 会被并发调用，避免重复构建。持有类实例不持有可变状态。
     * </p>
     */
    private volatile OpenAPI cachedApiDocs;

    public OpenApiDocController(OpenApiCustomizer openApiCustomiser, OpenApiProperties openApiProperties) {
        this.openApiCustomiser = openApiCustomiser;
        this.openApiProperties = openApiProperties;
    }

    @GetMapping("/v3/api-docs")
    public OpenAPI apiDocs() {
        OpenAPI cached = cachedApiDocs;
        if (cached == null) {
            synchronized (this) {
                cached = cachedApiDocs;
                if (cached == null) {
                    cached = buildApiDocs();
                    cachedApiDocs = cached;
                }
            }
        }
        return cached;
    }

    private OpenAPI buildApiDocs() {
        OpenAPI api = new OpenAPI();
        api.setPaths(new Paths());
        api.setInfo(new Info().title(openApiProperties.getTitle()).version(openApiProperties.getVersion())
                .description(openApiProperties.getDescription()));
        openApiCustomiser.customise(api);
        return api;
    }

    /**
     * Swagger UI 需要的配置端点，告诉 Swagger UI 去哪里加载 OpenAPI 规范。
     */
    @GetMapping("/v3/api-docs/swagger-config")
    public Map<String, Object> swaggerConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("url", "/v3/api-docs");
        config.put("configUrl", "/v3/api-docs/swagger-config");
        config.put("validatorUrl", "");
        config.put("oauth2RedirectUrl", "/swagger-ui/oauth2-redirect.html");
        Map<String, String> defaultUrl = new HashMap<>();
        defaultUrl.put("url", "/v3/api-docs");
        defaultUrl.put("name", openApiProperties.getTitle());
        config.put("urls", new Object[] { defaultUrl });
        return config;
    }

    @GetMapping("/swagger-ui.html")
    public ResponseEntity<Void> swaggerUiRedirect() {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create("/swagger-ui/index.html"));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }
}
