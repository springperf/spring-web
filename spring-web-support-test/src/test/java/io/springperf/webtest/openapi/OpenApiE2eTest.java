package io.springperf.webtest.openapi;

import io.springperf.webtest.BaseE2ETest;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiE2eTest extends BaseE2ETest {

    @Autowired(required = false)
    private OpenApiCustomizer openApiCustomizer;

    private OpenAPI buildApi() {
        assertNotNull(openApiCustomizer);
        OpenAPI api = new OpenAPI();
        api.setPaths(new io.swagger.v3.oas.models.Paths());
        openApiCustomizer.customise(api);
        return api;
    }

    @Test
    void openApiCustomizerBean_exists() {
        assertNotNull(openApiCustomizer,
                "OpenApiCustomizer bean should exist when springdoc-openapi-common is on classpath");
    }

    // ========= 路径覆盖 =========

    @Test
    void paths_containsAllControllerEndpoints() {
        OpenAPI api = buildApi();
        Map<String, PathItem> paths = api.getPaths();

        assertNotNull(paths);
        assertFalse(paths.isEmpty());

        // UserController 所有端点
        assertTrue(paths.containsKey("/demo/echo"), "missing /demo/echo");
        assertTrue(paths.containsKey("/demo/async"), "missing /demo/async");
        assertTrue(paths.containsKey("/demo/protected"), "missing /demo/protected");
        assertTrue(paths.containsKey("/demo/void-test"), "missing /demo/void-test");

        // 路径变量端点（正则被清理：{name:\\d+} → {name}，aaa* → aaa）
        assertTrue(paths.containsKey("/demo/hello/{name}/aaa"),
                "missing /demo/hello/{name}/aaa");
        assertTrue(paths.containsKey("/demo/create/{name}"),
                "missing /demo/create/{name}");
        assertTrue(paths.containsKey("/demo/find/{name}"),
                "missing /demo/find/{name}");
    }

    // ========= HTTP 方法 =========

    @Test
    void echoPath_hasGetAndPost() {
        OpenAPI api = buildApi();
        PathItem echoPath = api.getPaths().get("/demo/echo");
        assertNotNull(echoPath.getGet(), "/demo/echo should have GET");
        assertNotNull(echoPath.getPost(), "/demo/echo should have POST");
    }

    @Test
    void helloPath_hasGet() {
        OpenAPI api = buildApi();
        PathItem helloPath = api.getPaths().get("/demo/hello/{name}/aaa");
        assertNotNull(helloPath, "/demo/hello/{name}/aaa not found");
        assertNotNull(helloPath.getGet(), "/demo/hello/{name}/aaa should have GET");
    }

    @Test
    void createPath_hasPost() {
        OpenAPI api = buildApi();
        PathItem createPath = api.getPaths().get("/demo/create/{name}");
        assertNotNull(createPath, "/demo/create/{name} not found");
        assertNotNull(createPath.getPost(), "/demo/create/{name} should have POST");
    }

    @Test
    void findPath_hasPost() {
        OpenAPI api = buildApi();
        PathItem findPath = api.getPaths().get("/demo/find/{name}");
        assertNotNull(findPath, "/demo/find/{name} not found");
        assertNotNull(findPath.getPost(), "/demo/find/{name} should have POST");
    }

    // ========= 参数 =========

    @Test
    void pathParameters_extracted() {
        OpenAPI api = buildApi();
        Operation getOp = api.getPaths().get("/demo/hello/{name}/aaa").getGet();
        assertNotNull(getOp);

        boolean hasName = getOp.getParameters().stream()
                .anyMatch(p -> "name".equals(p.getName()) && "path".equals(p.getIn()));
        boolean hasV = getOp.getParameters().stream()
                .anyMatch(p -> "v".equals(p.getName()) && "query".equals(p.getIn()));
        assertTrue(hasName, "expected path param 'name'");
        assertTrue(hasV, "expected query param 'v'");
    }

    @Test
    void createEndpoint_hasBodyAndPathParam() {
        OpenAPI api = buildApi();
        Operation postOp = api.getPaths().get("/demo/create/{name}").getPost();
        assertNotNull(postOp);

        // 路径变量
        boolean hasName = postOp.getParameters().stream()
                .anyMatch(p -> "name".equals(p.getName()) && "path".equals(p.getIn()));
        assertTrue(hasName, "expected path param 'name'");

        // RequestBody
        assertNotNull(postOp.getRequestBody(), "expected request body");
    }

    @Test
    void findEndpoint_hasAllParameterTypes() {
        OpenAPI api = buildApi();
        Operation postOp = api.getPaths().get("/demo/find/{name}").getPost();
        assertNotNull(postOp);

        // 路径变量
        boolean hasName = postOp.getParameters().stream()
                .anyMatch(p -> "name".equals(p.getName()) && "path".equals(p.getIn()));
        assertTrue(hasName, "expected path param 'name'");

        // Query 参数：v、id
        boolean hasV = postOp.getParameters().stream()
                .anyMatch(p -> "v".equals(p.getName()) && "query".equals(p.getIn()));
        boolean hasId = postOp.getParameters().stream()
                .anyMatch(p -> "id".equals(p.getName()) && "query".equals(p.getIn()));
        assertTrue(hasV, "expected query param 'v'");
        assertTrue(hasId, "expected query param 'id'");

        // @ModelAttribute 参数
        boolean hasUser = postOp.getParameters().stream()
                .anyMatch(p -> "user".equals(p.getName()) && "query".equals(p.getIn()));
        assertTrue(hasUser, "expected @ModelAttribute param 'user'");

        // RequestBody
        assertNotNull(postOp.getRequestBody(), "expected request body");
    }

    // ========= 响应状态码 =========

    @Test
    void echoGet_returns302() {
        OpenAPI api = buildApi();
        Operation getOp = api.getPaths().get("/demo/echo").getGet();
        assertNotNull(getOp);
        assertNotNull(getOp.getResponses().get("302"),
                "@ResponseStatus(FOUND) should produce 302 response, got: " +
                        getOp.getResponses().keySet());
    }

    @Test
    void voidTest_returns204() {
        OpenAPI api = buildApi();
        Operation getOp = api.getPaths().get("/demo/void-test").getGet();
        assertNotNull(getOp);
        assertNotNull(getOp.getResponses().get("204"),
                "@ResponseStatus(NO_CONTENT) on void should produce 204, got: " +
                        getOp.getResponses().keySet());
    }

    @Test
    void echoPost_returns200() {
        OpenAPI api = buildApi();
        Operation postOp = api.getPaths().get("/demo/echo").getPost();
        assertNotNull(postOp);
        assertNotNull(postOp.getResponses().get("200"),
                "ResponseEntity.status(201) runtime status not available from annotation, got: " +
                        postOp.getResponses().keySet());
    }

    // ========= 异步端点 =========

    @Test
    void asyncEndpoint_returnTypeUnwrapped() {
        OpenAPI api = buildApi();
        Operation getOp = api.getPaths().get("/demo/async").getGet();
        assertNotNull(getOp);
        // CompletableFuture 应解包为 Map
        assertNotNull(getOp.getResponses().get("200"), "missing 200 response");
    }
}