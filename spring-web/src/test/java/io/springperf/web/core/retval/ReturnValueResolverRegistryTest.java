package io.springperf.web.core.retval;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReturnValueResolverRegistryTest {

    ReturnValueResolverRegistry registry;
    WebContext webContextMock;

    @BeforeEach
    void setUp() throws Exception {
        registry = new ReturnValueResolverRegistry();

        webContextMock = mock(WebContext.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(any(Class.class))).thenReturn(Collections.emptyMap());
        when(webContextMock.getCtx()).thenReturn(ctx);
        when(webContextMock.getWebComponent(any(Class.class))).thenReturn(null);
        when(webContextMock.getWebComponentWithDefault(any(Class.class), any())).thenReturn(mock(HttpBodyCodecRegistry.class));

        // Set protected webContext field via reflection
        Field f = registry.getClass().getSuperclass().getSuperclass().getDeclaredField("webContext");
        f.setAccessible(true);
        f.set(registry, webContextMock);
    }

    // ==================== skipResolve ====================

    @Test
    void skipResolve_nullReturnValue_returnsTrue() {
        assertTrue(registry.skipResolve(null, null, null, null));
    }

    @Test
    void skipResolve_handledResponse_returnsTrue() {
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.isHandled()).thenReturn(true);

        assertTrue(registry.skipResolve("value", null, null, resp));
    }

    @Test
    void skipResolve_normalValue_returnsFalse() {
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.isHandled()).thenReturn(false);

        assertFalse(registry.skipResolve("value", null, null, resp));
    }

    // ==================== getMethodReturnValueContext ====================

    @Test
    void getMethodReturnValueContext_nullMapping_returnsNull() {
        assertNull(registry.getMethodReturnValueContext(null, true));
    }

    @Test
    void getMethodReturnValueContext_cacheMiss_createsAndReturns() throws Exception {
        registry.initReturnValueResolver();
        Method method = TestController.class.getMethod("handle");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping, true);

        assertNotNull(ctx);
        assertNotNull(ctx.getReturnType());
        assertSame(ctx, mapping.get(ReturnValueResolverRegistry.MAPPING_CACHE_KEY));
    }

    @Test
    void getMethodReturnValueContext_cacheHit_returnsCached() throws Exception {
        registry.initReturnValueResolver();
        Method method = TestController.class.getMethod("handle");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        MethodReturnValueContext first = registry.getMethodReturnValueContext(mapping, true);
        MethodReturnValueContext second = registry.getMethodReturnValueContext(mapping, true);

        assertSame(first, second);
    }

    @Test
    void getMethodReturnValueContext_noCache_createsWithoutCaching() throws Exception {
        registry.initReturnValueResolver();
        Method method = TestController.class.getMethod("other");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping, false);

        assertNotNull(ctx);
        assertNull(mapping.get(ReturnValueResolverRegistry.MAPPING_CACHE_KEY));
    }

    // ==================== resolveReturnValue ====================

    @Test
    void resolveReturnValue_nullValue_skips() throws Exception {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        registry.resolveReturnValue(null, null, req, resp);

        verify(resp, never()).setHandled();
    }

    @Test
    void resolveReturnValue_cachedResolverResolves() throws Exception {
        registry.initReturnValueResolver();
        Method method = TestController.class.getMethod("handle");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping, true);
        ReturnValueResolver resolver = mock(ReturnValueResolver.class);
        when(resolver.supportsReturnValue("test", req, resp)).thenReturn(true);
        ctx.setReturnValueResolver(resolver);

        registry.resolveReturnValue("test", mapping, req, resp);

        verify(resolver).resolveReturnValue("test", ctx.getReturnType(), req, resp);
        verify(resp).setHandled();
    }

    @Test
    void resolveReturnValue_cachedResolverNotSupport_fallsBackToList() throws Exception {
        Method method = TestController.class.getMethod("handle");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        // Cached resolver (default mock, supportsReturnValue returns false)
        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping, true);
        ctx.setReturnValueResolver(mock(ReturnValueResolver.class));

        // Add a fallback resolver that supports the value
        ReturnValueResolver fallbackResolver = mock(ReturnValueResolver.class);
        when(fallbackResolver.supportsReturnValue("test", req, resp)).thenReturn(true);
        getResolversList().add(fallbackResolver);

        registry.resolveReturnValue("test", mapping, req, resp);

        verify(fallbackResolver).resolveReturnValue(eq("test"), any(), eq(req), eq(resp));
        verify(resp).setHandled();
    }

    @Test
    void resolveReturnValue_noResolverFound_doesNotSetHandled() throws Exception {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.isHandled()).thenReturn(false);

        // No resolvers initialized — no resolver matches
        registry.resolveReturnValue("test", null, req, resp);

        verify(resp, never()).setHandled();
    }

    // ==================== addResolver ====================

    @Test
    void addResolver_addsToList() {
        ReturnValueResolver resolver = mock(ReturnValueResolver.class);
        registry.addResolver(resolver);

        assertTrue(getResolversList().contains(resolver));
    }

    // ==================== initReturnValueResolver ====================

    @Test
    void initReturnValueResolver_registersAllResolvers() {
        registry.initReturnValueResolver();
        assertFalse(getResolversList().isEmpty());
    }

    // ==================== helpers ====================

    @SuppressWarnings("unchecked")
    private List<ReturnValueResolver> getResolversList() {
        try {
            Field f = ReturnValueResolverRegistry.class.getDeclaredField("resolvers");
            f.setAccessible(true);
            return (List<ReturnValueResolver>) f.get(registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unused")
    static class TestController {
        public String handle() { return "ok"; }
        public String other() { return "other"; }
    }
}
