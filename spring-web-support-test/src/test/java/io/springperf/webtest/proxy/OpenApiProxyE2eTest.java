package io.springperf.webtest.proxy;

import io.springperf.web.autoconfigure.openapi.OpenApiAdapter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E 测试：验证 CGLIB 代理 Controller 下 {@link OpenApiAdapter} 能正确解析注解。
 * <p>
 * 测试目标：
 * <ul>
 *   <li>{@code @ResponseStatus} 在代理方法上能被 {@code AnnotatedElementUtils.findMergedAnnotation} 解析</li>
 *   <li>{@code @PathVariable} / {@code @RequestParam} / {@code @RequestBody} 参数注解在代理下正常暴露</li>
 * </ul>
 *
 * @see OpenApiAdapter
 * @see OpenApiProxyController
 */
@SpringBootTest(
        classes = ProxyE2eApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=9092",
                "server.servlet.context-path=/api",
                "proxy.placeholder.path=/proxy/placeholder-resolved"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OpenApiProxyE2eTest {

    @Autowired(required = false)
    private OpenApiCustomiser openApiCustomiser;

    private OpenAPI buildApi() {
        assertNotNull(openApiCustomiser,
                "OpenApiCustomiser bean should exist when springdoc-openapi-common is on classpath");
        OpenAPI api = new OpenAPI();
        api.setPaths(new io.swagger.v3.oas.models.Paths());
        openApiCustomiser.customise(api);
        return api;
    }

    // ============ OpenApiCustomiser Bean ============

    @Test
    void openApiCustomiserBean_exists() {
        assertNotNull(openApiCustomiser,
                "OpenApiCustomiser should be available in proxy test context");
    }

    // ============ 路径覆盖 ============

    @Test
    void paths_containsAllOpenApiProxyEndpoints() {
        OpenAPI api = buildApi();
        Map<String, PathItem> paths = api.getPaths();

        assertNotNull(paths);
        assertFalse(paths.isEmpty());

        assertTrue(paths.containsKey("/openapi-proxy/create/{id}"),
                "missing /openapi-proxy/create/{id}");
        assertTrue(paths.containsKey("/openapi-proxy/update/{id}"),
                "missing /openapi-proxy/update/{id}");
        assertTrue(paths.containsKey("/openapi-proxy/query"),
                "missing /openapi-proxy/query");
        assertTrue(paths.containsKey("/openapi-proxy/delete/{id}"),
                "missing /openapi-proxy/delete/{id}");
    }

    // ============ @ResponseStatus 状态码 ============

    @Test
    void createEndpoint_responseStatus_201() {
        OpenAPI api = buildApi();
        Operation postOp = api.getPaths().get("/openapi-proxy/create/{id}").getPost();
        assertNotNull(postOp);
        assertNotNull(postOp.getResponses().get("201"),
                "@ResponseStatus(CREATED) should produce 201, got: " + postOp.getResponses().keySet());
    }

    @Test
    void updateEndpoint_responseStatus_202() {
        OpenAPI api = buildApi();
        Operation postOp = api.getPaths().get("/openapi-proxy/update/{id}").getPost();
        assertNotNull(postOp);
        assertNotNull(postOp.getResponses().get("202"),
                "@ResponseStatus(ACCEPTED) should produce 202, got: " + postOp.getResponses().keySet());
    }

    @Test
    void deleteEndpoint_responseStatus_204() {
        OpenAPI api = buildApi();
        Operation postOp = api.getPaths().get("/openapi-proxy/delete/{id}").getPost();
        assertNotNull(postOp);
        assertNotNull(postOp.getResponses().get("204"),
                "@ResponseStatus(NO_CONTENT) on void should produce 204, got: " + postOp.getResponses().keySet());
    }

    @Test
    void queryEndpoint_defaultResponseStatus_200() {
        OpenAPI api = buildApi();
        Operation getOp = api.getPaths().get("/openapi-proxy/query").getGet();
        assertNotNull(getOp);
        assertNotNull(getOp.getResponses().get("200"),
                "default (no @ResponseStatus) should produce 200, got: " + getOp.getResponses().keySet());
    }

    // ============ 参数 ============

    @Test
    void createEndpoint_hasPathAndQueryParam() {
        OpenAPI api = buildApi();
        Operation postOp = api.getPaths().get("/openapi-proxy/create/{id}").getPost();
        assertNotNull(postOp);

        boolean hasId = postOp.getParameters().stream()
                .anyMatch(p -> "id".equals(p.getName()) && "path".equals(p.getIn()));
        boolean hasName = postOp.getParameters().stream()
                .anyMatch(p -> "name".equals(p.getName()) && "query".equals(p.getIn()));
        assertTrue(hasId, "expected path param 'id'");
        assertTrue(hasName, "expected query param 'name'");
    }

    @Test
    void updateEndpoint_hasPathParamAndRequestBody() {
        OpenAPI api = buildApi();
        Operation postOp = api.getPaths().get("/openapi-proxy/update/{id}").getPost();
        assertNotNull(postOp);

        boolean hasId = postOp.getParameters().stream()
                .anyMatch(p -> "id".equals(p.getName()) && "path".equals(p.getIn()));
        assertTrue(hasId, "expected path param 'id'");

        assertNotNull(postOp.getRequestBody(), "expected request body");
    }

    @Test
    void queryEndpoint_hasQueryParams() {
        OpenAPI api = buildApi();
        Operation getOp = api.getPaths().get("/openapi-proxy/query").getGet();
        assertNotNull(getOp);

        boolean hasQ = getOp.getParameters().stream()
                .anyMatch(p -> "q".equals(p.getName()) && "query".equals(p.getIn()));
        boolean hasLimit = getOp.getParameters().stream()
                .anyMatch(p -> "limit".equals(p.getName()) && "query".equals(p.getIn()));
        assertTrue(hasQ, "expected query param 'q'");
        assertTrue(hasLimit, "expected query param 'limit'");
    }

    @Test
    void deleteEndpoint_hasPathParam() {
        OpenAPI api = buildApi();
        Operation postOp = api.getPaths().get("/openapi-proxy/delete/{id}").getPost();
        assertNotNull(postOp);

        boolean hasId = postOp.getParameters().stream()
                .anyMatch(p -> "id".equals(p.getName()) && "path".equals(p.getIn()));
        assertTrue(hasId, "expected path param 'id'");
    }
}