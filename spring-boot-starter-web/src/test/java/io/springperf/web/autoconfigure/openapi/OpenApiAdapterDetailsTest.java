package io.springperf.web.autoconfigure.openapi;

import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link OpenApiAdapter} 的纯逻辑方法： 路径清洗、返回值解包、Schema 映射、框架/简单类型判断。
 */
class OpenApiAdapterDetailsTest {

    static class Dto {
        public String name;
    }

    // ==================== cleanPathForOpenApi ====================

    @Test
    void cleanPath_regexVar_stripsConstraint() {
        assertEquals("/user/{id}", OpenApiAdapter.cleanPathForOpenApi("/user/{id:\\d+}"));
    }

    @Test
    void cleanPath_trailingDoubleStar_toAny() {
        assertEquals("/static/{any}", OpenApiAdapter.cleanPathForOpenApi("/static/**"));
    }

    @Test
    void cleanPath_trailingSingleStar_stripped() {
        assertEquals("/api/list", OpenApiAdapter.cleanPathForOpenApi("/api/list/*"));
    }

    @Test
    void cleanPath_bareStar_stripsStarButKeepsRemainder() {
        // 早期实现在首个星号处整段截断，/api/prefix*foo 与 /api/prefix*bar 会合并成同一个 /api/prefix
        assertEquals("/api/prefixfoo", OpenApiAdapter.cleanPathForOpenApi("/api/prefix*foo"));
        assertEquals("/api/prefixbar", OpenApiAdapter.cleanPathForOpenApi("/api/prefix*bar"));
    }

    @Test
    void cleanPath_starMidPath_keepsRoutesDistinct() {
        String a = OpenApiAdapter.cleanPathForOpenApi("/x/*/a");
        String b = OpenApiAdapter.cleanPathForOpenApi("/x/*/b");
        assertNotEquals(a, b, "中间含星号的不同后缀必须保持为不同路径，否则路由会在文档中合并");
    }

    @Test
    void cleanPath_nonWildcard_unchanged() {
        assertEquals("/user/{id}/posts", OpenApiAdapter.cleanPathForOpenApi("/user/{id}/posts"));
    }

    // ==================== resolveReturnType ====================

    static class ReturnTypes {
        @SuppressWarnings("unused")
        public CompletableFuture<Dto> futureDto() {
            return null;
        }

        @SuppressWarnings("unused")
        public String plainString() {
            return "";
        }
    }

    @Test
    void resolveReturnType_completableFuture_unwrapsGeneric() throws Exception {
        assertEquals(Dto.class, OpenApiAdapter.resolveReturnType(ReturnTypes.class.getMethod("futureDto")));
    }

    @Test
    void resolveReturnType_plainString_returnsType() throws Exception {
        assertEquals(String.class, OpenApiAdapter.resolveReturnType(ReturnTypes.class.getMethod("plainString")));
    }

    // ==================== resolveSchema ====================

    @Test
    void resolveSchema_primitivesAndWrappers() {
        assertEquals("string", OpenApiAdapter.resolveSchema(String.class).getType());
        assertEquals("integer", OpenApiAdapter.resolveSchema(Integer.class).getType());
        assertEquals("integer", OpenApiAdapter.resolveSchema(long.class).getType());
        assertEquals("number", OpenApiAdapter.resolveSchema(Double.class).getType());
        assertEquals("boolean", OpenApiAdapter.resolveSchema(boolean.class).getType());
    }

    @Test
    void resolveSchema_arrayAndIterable() {
        assertEquals("array", OpenApiAdapter.resolveSchema(String[].class).getType());
        assertEquals("array", OpenApiAdapter.resolveSchema(java.util.List.class).getType());
        assertNotNull(((Schema<?>) OpenApiAdapter.resolveSchema(String[].class).getItems()));
    }

    @Test
    void resolveSchema_pojo_returnsObject() {
        assertEquals("object", OpenApiAdapter.resolveSchema(Dto.class).getType());
    }

    // ==================== isFrameworkType / isSimpleType ====================

    @Test
    void isFrameworkType_servletAndSpringTypes() {
        assertTrue(OpenApiAdapter.isFrameworkType(jakarta.servlet.ServletRequest.class));
        assertTrue(OpenApiAdapter.isFrameworkType(org.springframework.http.HttpEntity.class));
        assertTrue(OpenApiAdapter.isFrameworkType(org.springframework.validation.BindingResult.class));
        assertFalse(OpenApiAdapter.isFrameworkType(String.class));
        assertFalse(OpenApiAdapter.isFrameworkType(Dto.class));
    }

    @Test
    void isSimpleType_various() {
        assertTrue(OpenApiAdapter.isSimpleType(int.class));
        assertTrue(OpenApiAdapter.isSimpleType(String.class));
        assertTrue(OpenApiAdapter.isSimpleType(Long.class));
        assertFalse(OpenApiAdapter.isSimpleType(Dto.class));
    }
}
