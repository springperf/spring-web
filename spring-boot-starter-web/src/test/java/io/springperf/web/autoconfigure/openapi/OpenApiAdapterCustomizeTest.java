package io.springperf.web.autoconfigure.openapi;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link OpenApiAdapter#customize} 主流程：从 MappingRegistry 路由
 * 构建 OpenAPI 路径/操作/参数/响应与标签。
 */
class OpenApiAdapterCustomizeTest {

    @RestController
    static class DemoController {
        @GetMapping
        @ResponseBody
        public Map<String, Object> find(@PathVariable("id") String id,
                                        @RequestParam("q") String q,
                                        @RequestParam(value = "page", required = false) int page) {
            return Collections.emptyMap();
        }

        @org.springframework.web.bind.annotation.PostMapping
        @ResponseBody
        public String create() {
            return "ok";
        }
    }

    // 用最小注解辅助，简化 controller 方法（避免引入过多 import）
    @java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD})
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @org.springframework.web.bind.annotation.RequestMapping(method = org.springframework.web.bind.annotation.RequestMethod.GET)
    @interface GetMapping {
    }

    @java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD})
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @org.springframework.web.bind.annotation.RequestMapping(method = org.springframework.web.bind.annotation.RequestMethod.POST)
    @interface PostMapping {
    }

    private PathMappingContext route(String methodName, Class<?>... paramTypes) throws Exception {
        HandlerMethod hm = new HandlerMethod(new DemoController(),
                DemoController.class.getMethod(methodName, paramTypes));
        List<Matcher> matchers = new ArrayList<>();
        matchers.add(new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET, HttpMethod.POST}));
        return new PathMappingContext(hm, matchers, "/demo/{id:\\d+}/list");
    }

    private WebContext webContext(PathMappingContext... routes) {
        WebContext wc = mock(WebContext.class);
        MappingRegistry registry = new MappingRegistry();
        for (PathMappingContext r : routes) {
            registry.registerMapping(r);
        }
        when(wc.getWebComponent(MappingRegistry.class)).thenReturn(registry);
        return wc;
    }

    @Test
    void customize_withRoutes_buildsPathsAndOperations() throws Exception {
        PathMappingContext route = route("find", String.class, String.class, int.class);
        OpenAPI openApi = new OpenAPI();
        OpenApiAdapter adapter = new OpenApiAdapter(webContext(route));
        adapter.customize(openApi);

        PathItem pathItem = openApi.getPaths().get("/demo/{id}/list");
        assertNotNull(pathItem, "路径应清洗后加入文档");
        assertNotNull(pathItem.getGet(), "GET 操作应生成");
        assertNotNull(pathItem.getPost(), "POST 操作应生成");

        Operation getOp = pathItem.getGet();
        assertEquals("find", getOp.getOperationId());
        assertNotNull(getOp.getResponses(), "应有响应");

        // 路径参数 id + 查询参数 q/page
        boolean hasPathId = false, hasQueryQ = false;
        for (Parameter p : getOp.getParameters()) {
            if ("id".equals(p.getName()) && "path".equals(p.getIn())) hasPathId = true;
            if ("q".equals(p.getName()) && "query".equals(p.getIn())) hasQueryQ = true;
        }
        assertTrue(hasPathId, "应有路径参数 id");
        assertTrue(hasQueryQ, "应有查询参数 q");
    }

    @Test
    void customize_noMappingRegistry_noop() {
        WebContext wc = mock(WebContext.class);
        when(wc.getWebComponent(MappingRegistry.class)).thenReturn(null);
        OpenApiAdapter adapter = new OpenApiAdapter(wc);
        OpenAPI openApi = new OpenAPI();
        assertDoesNotThrow(() -> adapter.customize(openApi));
    }

    @Test
    void customize_emptyRoutes_noop() {
        OpenApiAdapter adapter = new OpenApiAdapter(webContext());
        OpenAPI openApi = new OpenAPI();
        assertDoesNotThrow(() -> adapter.customize(openApi));
    }

    @Test
    void customize_addsTags() throws Exception {
        PathMappingContext route = route("find", String.class, String.class, int.class);
        OpenAPI openApi = new OpenAPI();
        new OpenApiAdapter(webContext(route)).customize(openApi);
        assertFalse(openApi.getTags().isEmpty(), "应添加 controller 标签");
    }
}