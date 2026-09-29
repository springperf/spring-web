package io.springperf.web.autoconfigure.openapi;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 补充 OpenApiAdapter 覆盖率：HTTP 方法/标签兜底、@RequestHeader/@ModelAttribute/
 *
 * @RequestBody/简单类型参数、void 响应与 PUT/DELETE/PATCH/HEAD/OPTIONS 操作映射。
 */
class OpenApiAdapterCoverageTest {

    static class ParamController {
        @SuppressWarnings("unused")
        public String headerParam(@RequestHeader(value = "X-Token", required = false) String token,
                @RequestHeader("X-Id") String id) {
            return "ok";
        }

        @SuppressWarnings("unused")
        public String modelParam(@ModelAttribute Filter filter) {
            return "ok";
        }

        @SuppressWarnings("unused")
        public String bodyParam(@RequestBody(required = false) Map<String, Object> body) {
            return "ok";
        }

        @SuppressWarnings("unused")
        public String simpleParam(String name, Integer age) {
            return "ok";
        }

        @SuppressWarnings("unused")
        public void noReturn() {
        }

        @SuppressWarnings("unused")
        @org.springframework.web.bind.annotation.ResponseStatus(code = org.springframework.http.HttpStatus.CREATED)
        public String created() {
            return "ok";
        }

        @SuppressWarnings("unused")
        @org.springframework.web.bind.annotation.ResponseStatus(
                code = org.springframework.http.HttpStatus.NOT_FOUND, reason = "用户不存在")
        public String customReason() {
            return "ok";
        }

        @SuppressWarnings("unused")
        public String beanBody(@RequestBody Payload payload) {
            return "ok";
        }

        @SuppressWarnings("unused")
        public String beanListBody(@RequestBody java.util.List<Payload> payloads) {
            return "ok";
        }

        @SuppressWarnings("unused")
        public String bindableModel(@ModelAttribute SearchForm form) {
            return "ok";
        }
    }

    /** 具备可读属性的载体：应被逐属性展开为 query 参数。 */
    public static class SearchForm {
        private String keyword;
        private int pageIndex;

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }

        public int getPageIndex() {
            return pageIndex;
        }

        public void setPageIndex(int pageIndex) {
            this.pageIndex = pageIndex;
        }
    }

    public static class Payload {
        private String title;
        private Integer count;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public Integer getCount() {
            return count;
        }

        public void setCount(Integer count) {
            this.count = count;
        }
    }

    static class Filter {
        @SuppressWarnings("unused")
        private String q;
    }

    private PathMappingContext route(String methodName, Class<?>... paramTypes) throws Exception {
        HandlerMethod hm = new HandlerMethod(new ParamController(),
                ParamController.class.getMethod(methodName, paramTypes));
        List<Matcher> matchers = new ArrayList<>();
        matchers.add(new HttpMethodMatcher(new HttpMethod[] { HttpMethod.GET }));
        return new PathMappingContext(hm, matchers, "/api/{id:\\d+}/x");
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
    void extractHttpMethods_withoutMethodMatcher_defaultsToGet() throws Exception {
        HandlerMethod hm = new HandlerMethod(new ParamController(), ParamController.class.getMethod("noReturn"));
        PathMappingContext ctx = new PathMappingContext(hm, Collections.emptyList(), "/x");
        OpenApiAdapter adapter = new OpenApiAdapter(webContext(ctx));
        OpenAPI openApi = new OpenAPI();
        adapter.customize(openApi);
        assertNotNull(openApi.getPaths().get("/x").getGet());
    }

    @Test
    void customize_customInvoker_noNpe() throws Exception {
        PathMappingContext ctx = new PathMappingContext(new io.springperf.web.core.invoker.CustomInvoker() {
            @Override
            public Object invoke(Object[] args) {
                return null;
            }

            @Override
            public java.lang.reflect.Method getHandleMethod() {
                try {
                    return ParamController.class.getMethod("noReturn");
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public List<Matcher> getMatchers() {
                return Collections.singletonList(new HttpMethodMatcher(new HttpMethod[] { HttpMethod.GET }));
            }

            @Override
            public String getType() {
                return "Test";
            }
        }, "/invoker");
        OpenApiAdapter adapter = new OpenApiAdapter(webContext(ctx));
        OpenAPI openApi = new OpenAPI();
        assertDoesNotThrow(() -> adapter.customize(openApi));
        assertNotNull(openApi.getPaths().get("/invoker").getGet());
    }

    @Test
    void customize_requestHeaderParams_addedAsHeader() throws Exception {
        PathMappingContext ctx = route("headerParam", String.class, String.class);
        OpenApiAdapter adapter = new OpenApiAdapter(webContext(ctx));
        OpenAPI openApi = new OpenAPI();
        adapter.customize(openApi);

        Operation op = openApi.getPaths().get("/api/{id}/x").getGet();
        boolean hasToken = false, hasId = false;
        for (Parameter p : op.getParameters()) {
            if ("X-Token".equals(p.getName()) && "header".equals(p.getIn()))
                hasToken = true;
            if ("X-Id".equals(p.getName()) && "header".equals(p.getIn()))
                hasId = true;
        }
        assertTrue(hasToken, "@RequestHeader(required=false) 应为 header 参数");
        assertTrue(hasId, "@RequestHeader 无显式 name 时应取参数名");
    }

    @Test
    void customize_modelAttributeParam_addedAsQuery() throws Exception {
        PathMappingContext ctx = route("modelParam", Filter.class);
        OpenApiAdapter adapter = new OpenApiAdapter(webContext(ctx));
        OpenAPI openApi = new OpenAPI();
        adapter.customize(openApi);

        Operation op = openApi.getPaths().get("/api/{id}/x").getGet();
        assertTrue(
                op.getParameters().stream().anyMatch(p -> "filter".equals(p.getName()) && "query".equals(p.getIn())));
    }

    @Test
    void customize_requestBodyParam_addsRequestBody() throws Exception {
        PathMappingContext ctx = route("bodyParam", Map.class);
        OpenApiAdapter adapter = new OpenApiAdapter(webContext(ctx));
        OpenAPI openApi = new OpenAPI();
        adapter.customize(openApi);

        Operation op = openApi.getPaths().get("/api/{id}/x").getGet();
        assertNotNull(op.getRequestBody());
        assertEquals("application/json", op.getRequestBody().getContent().keySet().iterator().next());
    }

    @Test
    void customize_simpleTypeParam_addedAsQuery() throws Exception {
        PathMappingContext ctx = route("simpleParam", String.class, Integer.class);
        OpenApiAdapter adapter = new OpenApiAdapter(webContext(ctx));
        OpenAPI openApi = new OpenAPI();
        adapter.customize(openApi);

        Operation op = openApi.getPaths().get("/api/{id}/x").getGet();
        boolean hasName = false, hasAge = false;
        for (Parameter p : op.getParameters()) {
            if ("name".equals(p.getName()))
                hasName = true;
            if ("age".equals(p.getName()) && "integer".equals(p.getSchema().getType()))
                hasAge = true;
        }
        assertTrue(hasName);
        assertTrue(hasAge);
    }

    @Test
    void customize_voidReturn_addsResponse() throws Exception {
        PathMappingContext ctx = route("noReturn");
        OpenApiAdapter adapter = new OpenApiAdapter(webContext(ctx));
        OpenAPI openApi = new OpenAPI();
        adapter.customize(openApi);

        Operation op = openApi.getPaths().get("/api/{id}/x").getGet();
        ApiResponse response = op.getResponses().get("200");
        assertNotNull(response);
    }

    @Test
    void customize_responseStatusReason_usedAsDescription() throws Exception {
        OpenAPI openApi = new OpenAPI();
        new OpenApiAdapter(webContext(route("created"))).customize(openApi);
        ApiResponse response = openApi.getPaths().get("/api/{id}/x").getGet().getResponses().get("201");
        assertNotNull(response, "应落到 @ResponseStatus 声明的 201");
        assertEquals("Created", response.getDescription(), "不得把所有状态码的描述都写成 OK");

        OpenAPI custom = new OpenAPI();
        new OpenApiAdapter(webContext(route("customReason"))).customize(custom);
        ApiResponse notFound = custom.getPaths().get("/api/{id}/x").getGet().getResponses().get("404");
        assertNotNull(notFound);
        assertEquals("用户不存在", notFound.getDescription(), "@ResponseStatus 的 reason 必须被保留");
    }

    @Test
    void customize_requestBodyBean_exposesProperties() throws Exception {
        OpenAPI openApi = new OpenAPI();
        new OpenApiAdapter(webContext(route("beanBody", Payload.class))).customize(openApi);

        Schema<?> schema = openApi.getPaths().get("/api/{id}/x").getGet().getRequestBody().getContent()
                .get("application/json").getSchema();
        assertNotNull(schema.getProperties(), "POJO 请求体必须展开出属性，而不是塌缩成裸 object");
        assertTrue(schema.getProperties().containsKey("title"), "属性列表: " + schema.getProperties().keySet());
        assertEquals("integer", ((Schema<?>) schema.getProperties().get("count")).getType());
    }

    @Test
    void customize_requestBodyBeanList_exposesElementSchema() throws Exception {
        OpenAPI openApi = new OpenAPI();
        new OpenApiAdapter(webContext(route("beanListBody", java.util.List.class))).customize(openApi);

        Schema<?> schema = openApi.getPaths().get("/api/{id}/x").getGet().getRequestBody().getContent()
                .get("application/json").getSchema();
        assertEquals("array", schema.getType(), "List<Payload> 应描述为 array: " + schema.getType());
        Schema<?> items = (Schema<?>) schema.getItems();
        assertNotNull(items.getProperties(), "元素类型必须展开出属性，而不是裸 object");
        assertTrue(items.getProperties().containsKey("title"), "元素属性: " + items.getProperties().keySet());
    }

    @Test
    void customize_modelAttributeWithBindableProperties_expandsToEachProperty() throws Exception {
        OpenAPI openApi = new OpenAPI();
        new OpenApiAdapter(webContext(route("bindableModel", SearchForm.class))).customize(openApi);

        Operation op = openApi.getPaths().get("/api/{id}/x").getGet();
        assertTrue(op.getParameters().stream().anyMatch(p -> "keyword".equals(p.getName()) && "query".equals(p.getIn())),
                "可绑定属性应逐个成为 query 参数: " + op.getParameters());
        assertTrue(op.getParameters().stream().anyMatch(p -> "pageIndex".equals(p.getName()) && "query".equals(p.getIn())));
        assertFalse(op.getParameters().stream().anyMatch(p -> "form".equals(p.getName())),
                "展开后不应再保留 object 类型的同名参数");
    }

    @Test
    void customize_otherHttpMethods_mapToPathItem() throws Exception {
        HandlerMethod hm = new HandlerMethod(new ParamController(), ParamController.class.getMethod("noReturn"));
        List<Matcher> matchers = new ArrayList<>();
        matchers.add(new HttpMethodMatcher(new HttpMethod[] { HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH,
                HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.POST }));
        PathMappingContext ctx = new PathMappingContext(hm, matchers, "/multi");
        OpenApiAdapter adapter = new OpenApiAdapter(webContext(ctx));
        OpenAPI openApi = new OpenAPI();
        adapter.customize(openApi);

        PathItem pathItem = openApi.getPaths().get("/multi");
        assertNotNull(pathItem.getPut());
        assertNotNull(pathItem.getDelete());
        assertNotNull(pathItem.getPatch());
        assertNotNull(pathItem.getHead());
        assertNotNull(pathItem.getOptions());
        assertNotNull(pathItem.getPost());
    }
}
