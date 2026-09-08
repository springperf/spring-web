package io.springperf.web.autoconfigure.openapi;

import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 楠岃瘉 {@link OpenApiAdapter} 鐨勭函閫昏緫鏂规硶锛?
 * 璺緞娓呮礂銆佽繑鍥炲€艰В鍖呫€丼chema 鏄犲皠銆佹鏋?绠€鍗曠被鍨嬪垽鏂€?
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
    void cleanPath_bareStar_cutAtStar() {
        assertEquals("/api/prefix", OpenApiAdapter.cleanPathForOpenApi("/api/prefix*foo"));
    }

    @Test
    void cleanPath_nonWildcard_unchanged() {
        assertEquals("/user/{id}/posts", OpenApiAdapter.cleanPathForOpenApi("/user/{id}/posts"));
    }

    // ==================== resolveReturnType ====================

    static class ReturnTypes {
        @SuppressWarnings("unused")
        public CompletableFuture<Dto> futureDto() { return null; }

        @SuppressWarnings("unused")
        public String plainString() { return ""; }
    }

    @Test
    void resolveReturnType_completableFuture_unwrapsGeneric() throws Exception {
        assertEquals(Dto.class, OpenApiAdapter.resolveReturnType(
                ReturnTypes.class.getMethod("futureDto")));
    }

    @Test
    void resolveReturnType_plainString_returnsType() throws Exception {
        assertEquals(String.class, OpenApiAdapter.resolveReturnType(
                ReturnTypes.class.getMethod("plainString")));
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
        assertTrue(OpenApiAdapter.isFrameworkType(javax.servlet.ServletRequest.class));
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