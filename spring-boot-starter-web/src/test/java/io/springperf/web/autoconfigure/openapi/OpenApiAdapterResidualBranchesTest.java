package io.springperf.web.autoconfigure.openapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.Matcher;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponses;

/** 按聚合 JaCoCo 行级缺口收口 customize 防御分支与 schema/参数解析的复合条件。 */
class OpenApiAdapterResidualBranchesTest {

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = OpenApiAdapter.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static Method method(String name, Class<?>... paramTypes) throws Exception {
        return Samples.class.getDeclaredMethod(name, paramTypes);
    }

    private static PathMappingContext route(String pathRule) {
        PathMappingContext ctx = mock(PathMappingContext.class);
        doReturn(pathRule).when(ctx).getPathRule();
        when(ctx.getMatchers()).thenReturn(new Matcher[0]);
        return ctx;
    }

    private static PathMappingContext routeWithMethod(String pathRule) throws Exception {
        PathMappingContext ctx = route(pathRule);
        when(ctx.getMethod()).thenReturn(method("plain"));
        doReturn(Samples.class).when(ctx).getBeanType();
        return ctx;
    }

    private static WebContext contextWith(PathMappingContext... routes) {
        MappingRegistry registry = mock(MappingRegistry.class);
        when(registry.getMappingContextList()).thenReturn(List.of(routes));
        WebContext wc = mock(WebContext.class);
        when(wc.getWebComponent(MappingRegistry.class)).thenReturn(registry);
        return wc;
    }

    @Test
    void customize_nullPathRule_skipsRouteWithoutWritingDocument() {
        OpenAPI openApi = new OpenAPI();

        new OpenApiAdapter(contextWith(route(null))).customize(openApi);

        assertNull(openApi.getPaths(), "null 路径规则必须整条跳过，不得产生空路径");
        assertNull(openApi.getTags(), "跳过全部路由时不应生成孤立标签");
    }

    @Test
    void customize_blankContextPath_doesNotPrefix() throws Exception {
        WebContext wc = contextWith(routeWithMethod("/plain"));
        when(wc.getContextPath()).thenReturn("");
        OpenAPI openApi = new OpenAPI();

        new OpenApiAdapter(wc).customize(openApi);

        assertNotNull(openApi.getPaths().get("/plain"), "空 context-path 不应产生前缀");
    }

    @Test
    void buildOperation_nullMethod_keepsTagAndEmptyResponses() throws Exception {
        Set<String> usedIds = new LinkedHashSet<>();

        Operation op = (Operation) invoke(new OpenApiAdapter(mock(WebContext.class)), "buildOperation",
                new Class<?>[] { PathMappingContext.class, Method.class, String.class, Set.class },
                route("/no-method"), null, "Endpoints", usedIds);

        assertNull(op.getOperationId(), "无 Method 时不得伪造 operationId");
        assertNull(op.getSummary());
        assertNull(op.getDescription());
        assertTrue(op.getTags().contains("Endpoints"));
        assertNotNull(op.getResponses());
        assertTrue(op.getResponses().isEmpty(), "无 Method 时不得凭空生成响应 schema");
        assertTrue(usedIds.isEmpty(), "无 Method 时不应占用 operationId");
    }

    @Test
    void addMethodParameters_requestHeaderWithoutValueUsesParameterName() throws Exception {
        Method m = method("headerNoValue", String.class);
        Operation operation = new Operation();

        invoke(new OpenApiAdapter(mock(WebContext.class)), "addMethodParameters",
                new Class<?>[] { Method.class, Operation.class }, m, operation);

        assertEquals(1, operation.getParameters().size());
        assertEquals("header", operation.getParameters().get(0).getIn());
        assertEquals(m.getParameters()[0].getName(), operation.getParameters().get(0).getName(),
                "@RequestHeader 未显式命名时应取反射参数名");
    }

    @Test
    void addMethodParameters_modelAttributeOnGuardedTypeKeepsSingleParam() throws Exception {
        Method m = method("modelList", List.class);
        Operation operation = new Operation();

        invoke(new OpenApiAdapter(mock(WebContext.class)), "addMethodParameters",
                new Class<?>[] { Method.class, Operation.class }, m, operation);

        assertEquals(1, operation.getParameters().size(), "无可读属性时应退化为单个参数");
        assertEquals("query", operation.getParameters().get(0).getIn());
        assertEquals("array", operation.getParameters().get(0).getSchema().getType());
    }

    @Test
    void resolveRequestSchema_nestedGenericFallsBackToRawCollection() throws Exception {
        Parameter param = method("nestedBody", List.class).getParameters()[0];

        Schema<?> schema = (Schema<?>) invoke(null, "resolveRequestSchema",
                new Class<?>[] { Parameter.class }, param);

        assertEquals("array", schema.getType());
        assertEquals("object", schema.getItems().getType(), "嵌套泛型无法取到 Class 实参时应退回裸集合");
    }

    @Test
    void addResponse_boxedVoidReturnSkipsContent() throws Exception {
        Operation operation = new Operation();
        operation.setResponses(new ApiResponses());

        invoke(new OpenApiAdapter(mock(WebContext.class)), "addResponse",
                new Class<?>[] { Method.class, Operation.class }, method("boxedVoid"), operation);

        var response = operation.getResponses().get("200");
        assertNotNull(response);
        assertEquals("OK", response.getDescription());
        assertNull(response.getContent(), "Void 返回类型不应生成 JSON schema");
    }

    @Test
    void setPathItemOperation_unknownVerbLeavesPathItemEmpty() throws Exception {
        PathItem item = new PathItem();

        invoke(new OpenApiAdapter(mock(WebContext.class)), "setPathItemOperation",
                new Class<?>[] { PathItem.class, HttpMethod.class, Operation.class },
                item, HttpMethod.TRACE, new Operation());

        assertNull(item.getGet());
        assertNull(item.getPost());
        assertNull(item.getPut());
        assertNull(item.getDelete());
        assertNull(item.getPatch());
        assertNull(item.getHead());
        assertNull(item.getOptions());
    }

    @Test
    void resolveProperties_guardRejectsNonBindableTypes() throws Exception {
        Class<?>[] guarded = { null, int.class, String[].class, SampleEnum.class, List.class, Map.class,
                String.class, javax.crypto.Cipher.class, jakarta.servlet.ServletRequest.class };
        for (Class<?> type : guarded) {
            Object result = invoke(null, "resolveProperties",
                    new Class<?>[] { Class.class, Set.class }, type, new LinkedHashSet<Class<?>>());
            assertNull(result, "守卫应拒绝: " + (type == null ? "null" : type.getName()));
        }

        Set<Class<?>> visiting = new LinkedHashSet<>();
        visiting.add(Samples.class);
        assertNull(invoke(null, "resolveProperties", new Class<?>[] { Class.class, Set.class },
                Samples.class, visiting), "visiting 命中必须掐断递归展开");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveProperties_writeOnlyPropertyIsSkipped() throws Exception {
        Map<String, Schema> properties = (Map<String, Schema>) invoke(null, "resolveProperties",
                new Class<?>[] { Class.class, Set.class }, WriteOnlyDto.class, new LinkedHashSet<Class<?>>());

        assertNotNull(properties);
        assertFalse(properties.containsKey("writeOnly"), "只写属性没有 readMethod，不能进文档");
    }

    static class Samples {
        static void headerNoValue(@RequestHeader String token) {
        }

        static void modelList(@ModelAttribute List<String> values) {
        }

        static void nestedBody(@RequestBody List<List<String>> payloads) {
        }

        static Void boxedVoid() {
            return null;
        }

        static String plain() {
            return null;
        }
    }

    enum SampleEnum {
        A, B
    }

    public static class WriteOnlyDto {
        public void setWriteOnly(String value) {
        }
    }
}
