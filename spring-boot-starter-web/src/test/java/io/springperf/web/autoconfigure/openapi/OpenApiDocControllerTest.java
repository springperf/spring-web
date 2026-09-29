package io.springperf.web.autoconfigure.openapi;

import io.springperf.web.autoconfigure.OpenApiProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
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
        OpenApiCustomizer customizer = mock(OpenApiCustomizer.class);
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
    void apiDocs_isCachedAcrossCalls() {
        OpenApiCustomizer customizer = mock(OpenApiCustomizer.class);
        OpenApiDocController controller = new OpenApiDocController(customizer, props());

        OpenAPI first = controller.apiDocs();
        OpenAPI second = controller.apiDocs();

        // 路由表运行期不变，文档内容恒定；不缓存则每次都要重新反射展开全部 POJO（与路由数成正比）
        assertSame(first, second, "重复调用应返回同一缓存实例");
        verify(customizer, times(1)).customise(any(OpenAPI.class));
    }

    @Test
    void apiDocs_concurrentFirstCall_buildsOnce() throws Exception {
        OpenApiCustomizer customizer = mock(OpenApiCustomizer.class);
        OpenApiDocController controller = new OpenApiDocController(customizer, props());

        int threads = 16;
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(threads);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        java.util.Set<OpenAPI> results = java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(controller.apiDocs());
                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, results.size(), "并发首次调用只应构建一个实例");
        verify(customizer, times(1)).customise(any(OpenAPI.class));
    }

    @Test
    void swaggerConfig_returnsUiConfigMap() {
        OpenApiCustomizer customizer = mock(OpenApiCustomizer.class);
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
        OpenApiCustomizer customizer = mock(OpenApiCustomizer.class);
        OpenApiDocController controller = new OpenApiDocController(customizer, props());

        ResponseEntity<Void> resp = controller.swaggerUiRedirect();

        assertEquals(HttpStatus.FOUND, resp.getStatusCode());
        assertEquals("/swagger-ui/index.html", resp.getHeaders().getLocation().toString());
    }
}
