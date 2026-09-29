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

    /**
     * 文档中的路径必须带 context-path 前缀：本测试应用配置了 {@code server.servlet.context-path=/api} （见
     * {@code spring-web-support-test/src/main/resources/application.properties}）， 对外真实 URL 是
     * {@code /api/demo/echo}。OpenApiAdapter 生成的 paths 与之一致， 故断言也必须带上该前缀 —— 否则断言的是「客户端按文档调用会 404」的错误期望。
     */
    private static final String API_PREFIX = "/api";

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
        assertTrue(paths.containsKey(API_PREFIX + "/demo/echo"), "missing /demo/echo");
        assertTrue(paths.containsKey(API_PREFIX + "/demo/async"), "missing /demo/async");
        assertTrue(paths.containsKey(API_PREFIX + "/demo/protected"), "missing /demo/protected");
        assertTrue(paths.containsKey(API_PREFIX + "/demo/void-test"), "missing /demo/void-test");

        // 路径变量端点（正则被清理：{name:\\d+} → {name}，aaa* → aaa）
        assertTrue(paths.containsKey(API_PREFIX + "/demo/hello/{name}/aaa"), "missing /demo/hello/{name}/aaa");
        assertTrue(paths.containsKey(API_PREFIX + "/demo/create/{name}"), "missing /demo/create/{name}");
        assertTrue(paths.containsKey(API_PREFIX + "/demo/find/{name}"), "missing /demo/find/{name}");
    }

    // ========= HTTP 方法 =========

    @Test
    void echoPath_hasGetAndPost() {
        OpenAPI api = buildApi();
        PathItem echoPath = api.getPaths().get(API_PREFIX + "/demo/echo");
        assertNotNull(echoPath.getGet(), "/demo/echo should have GET");
        assertNotNull(echoPath.getPost(), "/demo/echo should have POST");
    }

    @Test
    void helloPath_hasGet() {
        OpenAPI api = buildApi();
        PathItem helloPath = api.getPaths().get(API_PREFIX + "/demo/hello/{name}/aaa");
        assertNotNull(helloPath, "/demo/hello/{name}/aaa not found");
        assertNotNull(helloPath.getGet(), "/demo/hello/{name}/aaa should have GET");
    }

    @Test
    void createPath_hasPost() {
        OpenAPI api = buildApi();
        PathItem createPath = api.getPaths().get(API_PREFIX + "/demo/create/{name}");
        assertNotNull(createPath, "/demo/create/{name} not found");
        assertNotNull(createPath.getPost(), "/demo/create/{name} should have POST");
    }

    @Test
    void findPath_hasPost() {
        OpenAPI api = buildApi();
        PathItem findPath = api.getPaths().get(API_PREFIX + "/demo/find/{name}");
        assertNotNull(findPath, "/demo/find/{name} not found");
        assertNotNull(findPath.getPost(), "/demo/find/{name} should have POST");
    }

    // ========= 参数 =========

    @Test
    void pathParameters_extracted() {
        OpenAPI api = buildApi();
        Operation getOp = api.getPaths().get(API_PREFIX + "/demo/hello/{name}/aaa").getGet();
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
        Operation postOp = api.getPaths().get(API_PREFIX + "/demo/create/{name}").getPost();
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
        Operation postOp = api.getPaths().get(API_PREFIX + "/demo/find/{name}").getPost();
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

        // @ModelAttribute 参数：按可绑定属性逐个展开为 query 参数（User 有 name/age 两个属性），
        // 不再是单个名为 "user" 的 object 参数 —— 后者客户端无从得知该传什么。
        // 注：User.name 与 @PathVariable name 同名，故 name 同时以 path 与 query 两种形式存在。
        boolean hasAge = postOp.getParameters().stream()
                .anyMatch(p -> "age".equals(p.getName()) && "query".equals(p.getIn()));
        assertTrue(hasAge, "expected @ModelAttribute property 'age' as query param");
        boolean hasUserPropertyName = postOp.getParameters().stream()
                .anyMatch(p -> "name".equals(p.getName()) && "query".equals(p.getIn()));
        assertTrue(hasUserPropertyName, "expected @ModelAttribute property 'name' as query param");
        boolean noRawUserParam = postOp.getParameters().stream().noneMatch(p -> "user".equals(p.getName()));
        assertTrue(noRawUserParam, "@ModelAttribute 应展开为属性，不应保留同名的 object 参数");

        // RequestBody
        assertNotNull(postOp.getRequestBody(), "expected request body");
    }

    // ========= 响应状态码 =========

    @Test
    void echoGet_returns302() {
        OpenAPI api = buildApi();
        Operation getOp = api.getPaths().get(API_PREFIX + "/demo/echo").getGet();
        assertNotNull(getOp);
        assertNotNull(getOp.getResponses().get("302"),
                "@ResponseStatus(FOUND) should produce 302 response, got: " + getOp.getResponses().keySet());
    }

    @Test
    void voidTest_returns204() {
        OpenAPI api = buildApi();
        Operation getOp = api.getPaths().get(API_PREFIX + "/demo/void-test").getGet();
        assertNotNull(getOp);
        assertNotNull(getOp.getResponses().get("204"),
                "@ResponseStatus(NO_CONTENT) on void should produce 204, got: " + getOp.getResponses().keySet());
    }

    @Test
    void echoPost_returns200() {
        OpenAPI api = buildApi();
        Operation postOp = api.getPaths().get(API_PREFIX + "/demo/echo").getPost();
        assertNotNull(postOp);
        assertNotNull(postOp.getResponses().get("200"),
                "ResponseEntity.status(201) runtime status not available from annotation, got: "
                        + postOp.getResponses().keySet());
    }

    // ========= 异步端点 =========

    @Test
    void asyncEndpoint_returnTypeUnwrapped() {
        OpenAPI api = buildApi();
        Operation getOp = api.getPaths().get(API_PREFIX + "/demo/async").getGet();
        assertNotNull(getOp);
        // CompletableFuture 应解包为 Map
        assertNotNull(getOp.getResponses().get("200"), "missing 200 response");
    }
}
