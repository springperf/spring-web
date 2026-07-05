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
 * <p>Activated when {@link OpenApiCustomizer} (springdoc-openapi-common) is on the classpath.
 * Users can override by defining their own {@code OpenApiDocController} bean.</p>
 */
@RestController
public class OpenApiDocController {

    private final OpenApiCustomizer openApiCustomiser;
    private final OpenApiProperties openApiProperties;

    public OpenApiDocController(OpenApiCustomizer openApiCustomiser, OpenApiProperties openApiProperties) {
        this.openApiCustomiser = openApiCustomiser;
        this.openApiProperties = openApiProperties;
    }

    @GetMapping("/v3/api-docs")
    public OpenAPI apiDocs() {
        OpenAPI api = new OpenAPI();
        api.setPaths(new Paths());
        api.setInfo(new Info()
                .title(openApiProperties.getTitle())
                .version(openApiProperties.getVersion())
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
        config.put("urls", new Object[]{defaultUrl});
        return config;
    }

    @GetMapping("/swagger-ui.html")
    public ResponseEntity<Void> swaggerUiRedirect() {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create("/swagger-ui/index.html"));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }
}