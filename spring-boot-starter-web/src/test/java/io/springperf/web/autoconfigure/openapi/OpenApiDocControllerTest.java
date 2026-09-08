package io.springperf.web.autoconfigure.openapi;

import io.springperf.web.autoconfigure.OpenApiProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OpenApiDocControllerTest {

    private OpenApiProperties props() {
        OpenApiProperties p = new OpenApiProperties();
        p.setTitle("My API");
        p.setVersion("2.0.0");
        p.setDescription("desc");
        return p;
    }

    @Test
    void apiDocs_buildsInfoAndAppliesCustomizer() {
        OpenApiCustomiser customizer = mock(OpenApiCustomiser.class);
        OpenApiDocController controller = new OpenApiDocController(customizer, props());

        OpenAPI api = controller.apiDocs();

        assertNotNull(api);
        assertTrue(api.getPaths() instanceof Paths);
        Info info = api.getInfo();
        assertEquals("My API", info.getTitle());
        assertEquals("2.0.0", info.getVersion());
        assertEquals("desc", info.getDescription());
        verify(customizer).customise(any(OpenAPI.class));
    }

    @Test
    void swaggerConfig_returnsUiConfigMap() {
        OpenApiCustomiser customizer = mock(OpenApiCustomiser.class);
        OpenApiDocController controller = new OpenApiDocController(customizer, props());

        Map<String, Object> config = controller.swaggerConfig();

        assertEquals("/v3/api-docs", config.get("url"));
        assertEquals("/v3/api-docs/swagger-config", config.get("configUrl"));
        assertEquals("/swagger-ui/oauth2-redirect.html", config.get("oauth2RedirectUrl"));
        Object[] urls = (Object[]) config.get("urls");
        assertEquals(1, urls.length);
        Map<?, ?> defaultUrl = (Map<?, ?>) urls[0];
        assertEquals("My API", defaultUrl.get("name"));
    }

    @Test
    void swaggerUiRedirect_returns302ToIndex() {
        OpenApiCustomiser customizer = mock(OpenApiCustomiser.class);
        OpenApiDocController controller = new OpenApiDocController(customizer, props());

        ResponseEntity<Void> resp = controller.swaggerUiRedirect();

        assertEquals(HttpStatus.FOUND, resp.getStatusCode());
        assertEquals("/swagger-ui/index.html", resp.getHeaders().getLocation().toString());
    }
}