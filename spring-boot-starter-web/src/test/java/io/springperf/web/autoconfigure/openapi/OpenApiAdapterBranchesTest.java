package io.springperf.web.autoconfigure.openapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.validation.BindingResult;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.Matcher;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;

/** 按 JaCoCo 行级缺口精确覆盖 schema 判定、路径变量解析与 customize 早退分支。 */
class OpenApiAdapterBranchesTest {

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = OpenApiAdapter.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void resolveSchema_coversEveryScalarArrayAndObjectBranch() {
        assertEquals("string", OpenApiAdapter.resolveSchema(String.class).getType());
        assertEquals("int32", OpenApiAdapter.resolveSchema(int.class).getFormat());
        assertEquals("int32", OpenApiAdapter.resolveSchema(Integer.class).getFormat());
        assertEquals("int64", OpenApiAdapter.resolveSchema(long.class).getFormat());
        assertEquals("int64", OpenApiAdapter.resolveSchema(Long.class).getFormat());
        assertEquals("double", OpenApiAdapter.resolveSchema(double.class).getFormat());
        assertEquals("double", OpenApiAdapter.resolveSchema(Double.class).getFormat());
        assertEquals("float", OpenApiAdapter.resolveSchema(float.class).getFormat());
        assertEquals("float", OpenApiAdapter.resolveSchema(Float.class).getFormat());
        assertEquals("boolean", OpenApiAdapter.resolveSchema(boolean.class).getType());
        assertEquals("boolean", OpenApiAdapter.resolveSchema(Boolean.class).getType());
        assertEquals("string", OpenApiAdapter.resolveSchema(String[].class).getItems().getType());
        assertEquals("array", OpenApiAdapter.resolveSchema(String[].class).getType());
        assertEquals("object", OpenApiAdapter.resolveSchema(List.class).getItems().getType());
        assertEquals("object", OpenApiAdapter.resolveSchema(SimpleDto.class).getType());
    }

    @Test
    void resolveSchema_selfReferencingDtoStopsAtVisiting() {
        Schema<?> schema = OpenApiAdapter.resolveSchema(SelfDto.class);

        assertEquals("object", schema.getType());
        assertNotNull(schema.getProperties());
        assertTrue(schema.getProperties().containsKey("self"), "自引用属性仍应出现在 schema 中");
        assertEquals("object", schema.getProperties().get("self").getType());
        assertNull(schema.getProperties().get("self").getProperties(), "循环引用必须被 visiting 掐断");
    }

    @Test
    void isFrameworkType_coversAllSupportedTypes() {
        assertTrue(OpenApiAdapter.isFrameworkType(jakarta.servlet.ServletRequest.class));
        assertTrue(OpenApiAdapter.isFrameworkType(HttpEntity.class));
        assertTrue(OpenApiAdapter.isFrameworkType(RequestEntity.class));
        assertTrue(OpenApiAdapter.isFrameworkType(BindingResult.class));
        assertTrue(OpenApiAdapter.isFrameworkType(Principal.class));
        assertFalse(OpenApiAdapter.isFrameworkType(String.class));
    }

    @Test
    void isSimpleType_coversEverySupportedAndRejectedType() {
        for (Class<?> type : new Class<?>[] { int.class, String.class, Integer.class, Long.class, Double.class,
                Float.class, Boolean.class, Date.class, LocalDate.class, LocalDateTime.class }) {
            assertTrue(OpenApiAdapter.isSimpleType(type), type.getSimpleName());
        }
        assertFalse(OpenApiAdapter.isSimpleType(Object.class));
        assertFalse(OpenApiAdapter.isSimpleType(List.class));
    }

    @Test
    void resolveReturnType_unwrapsFutureGenericsAndKeepsRawTypes() throws Exception {
        assertEquals(String.class, OpenApiAdapter.resolveReturnType(method("futureString")));
        assertEquals(Future.class, OpenApiAdapter.resolveReturnType(method("futureList")));
        assertEquals(CompletableFuture.class, OpenApiAdapter.resolveReturnType(method("rawFuture")));
        assertEquals(String.class, OpenApiAdapter.resolveReturnType(method("plain")));
        assertEquals(void.class, OpenApiAdapter.resolveReturnType(method("voidMethod")));
    }

    @Test
    void addPathParameters_handlesUnclosedAndRegexVariables() throws Exception {
        OpenApiAdapter adapter = new OpenApiAdapter(mock(WebContext.class));

        Operation unclosed = new Operation();
        call(adapter, "addPathParameters", new Class<?>[] { String.class, Operation.class }, "/api/{id", unclosed);
        assertTrue(unclosed.getParameters() == null || unclosed.getParameters().isEmpty());

        Operation regex = new Operation();
        call(adapter, "addPathParameters", new Class<?>[] { String.class, Operation.class }, "/api/{id:[0-9]+}", regex);
        assertEquals(1, regex.getParameters().size());
        assertEquals("id", regex.getParameters().get(0).getName());

        Operation multiple = new Operation();
        call(adapter, "addPathParameters", new Class<?>[] { String.class, Operation.class }, "/api/{a}/{b}", multiple);
        assertEquals(2, multiple.getParameters().size());
        assertEquals("b", multiple.getParameters().get(1).getName());
    }

    @Test
    void extractTagName_nullBeanTypeFallsBackToEndpoints() throws Exception {
        OpenApiAdapter adapter = new OpenApiAdapter(mock(WebContext.class));
        PathMappingContext ctx = mock(PathMappingContext.class);
        doReturn(null).when(ctx).getBeanType();
        assertEquals("Endpoints", call(adapter, "extractTagName", new Class<?>[] { PathMappingContext.class }, ctx));

        doReturn(SampleController.class).when(ctx).getBeanType();
        assertEquals("Sample", call(adapter, "extractTagName", new Class<?>[] { PathMappingContext.class }, ctx));
    }

    @Test
    void setPathItemOperation_optionsIsMapped() throws Exception {
        OpenApiAdapter adapter = new OpenApiAdapter(mock(WebContext.class));
        PathItem item = new PathItem();
        Operation operation = new Operation().operationId("optionsOp");

        call(adapter, "setPathItemOperation", new Class<?>[] { PathItem.class, HttpMethod.class, Operation.class },
                item, HttpMethod.OPTIONS, operation);

        assertSame(operation, item.getOptions());
    }

    @Test
    void customize_skipsMissingRegistryAndBlankPaths() {
        WebContext withoutRegistry = mock(WebContext.class);
        OpenAPI first = new OpenAPI();
        new OpenApiAdapter(withoutRegistry).customize(first);
        assertNull(first.getPaths());

        PathMappingContext blank = blankPathContext();
        MappingRegistry registry = mock(MappingRegistry.class);
        when(registry.getMappingContextList()).thenReturn(null, Collections.emptyList(), List.of(blank));
        WebContext wc = mock(WebContext.class);
        when(wc.getWebComponent(MappingRegistry.class)).thenReturn(registry);
        OpenApiAdapter adapter = new OpenApiAdapter(wc);

        for (int i = 0; i < 3; i++) {
            OpenAPI openApi = new OpenAPI();
            adapter.customize(openApi);
            assertNull(openApi.getPaths(), "第 " + i + " 次：null/空映射或空路径不应写入文档");
        }
    }

    @Test
    void customize_buildsPathWithParametersAndResponse() throws Exception {
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/{id}");
        when(ctx.getMatchers()).thenReturn(new Matcher[0]);
        doReturn(SampleController.class).when(ctx).getBeanType();
        when(ctx.getMethod()).thenReturn(method("plain"));
        MappingRegistry registry = mock(MappingRegistry.class);
        when(registry.getMappingContextList()).thenReturn(List.of(ctx));
        WebContext wc = mock(WebContext.class);
        when(wc.getWebComponent(MappingRegistry.class)).thenReturn(registry);
        OpenAPI openApi = new OpenAPI();

        new OpenApiAdapter(wc).customize(openApi);

        PathItem item = openApi.getPaths().get("/api/{id}");
        assertNotNull(item);
        assertNotNull(item.getGet(), "无 HttpMethodMatcher 时应回退 GET");
        assertEquals("plain", item.getGet().getOperationId());
        assertEquals(1, item.getGet().getParameters().size());
        assertEquals("id", item.getGet().getParameters().get(0).getName());
        assertTrue(item.getGet().getResponses().containsKey("200"));
        assertEquals(1, openApi.getTags().size());
        assertEquals("Sample", openApi.getTags().get(0).getName());
    }

    private static PathMappingContext blankPathContext() {
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("");
        return ctx;
    }

    private static Method method(String name) throws Exception {
        return OpenApiAdapterBranchesTest.class.getDeclaredMethod(name);
    }

    static void voidMethod() {
    }

    static CompletableFuture<String> futureString() {
        return null;
    }

    static Future<List<String>> futureList() {
        return null;
    }

    @SuppressWarnings("rawtypes")
    static CompletableFuture rawFuture() {
        return null;
    }

    static String plain() {
        return null;
    }

    static class SimpleDto {
        public String getName() {
            return "dto";
        }
    }

    static class SelfDto {
        public String getName() {
            return "self";
        }

        public SelfDto getSelf() {
            return this;
        }
    }

    static class SampleController {
    }
}
