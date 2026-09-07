package io.springperf.web.core.retval;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

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
    void skipResolve_voidMethod_setsHandled() throws Exception {
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.isHandled()).thenReturn(false);
        Method method = TestController.class.getMethod("voidMethod");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        boolean skipped = registry.skipResolve(null, mapping, null, resp);

        assertTrue(skipped);
        verify(resp).setHandled();
    }

    @Test
    void skipResolve_nullMethodReturnValue_nonVoid_doesNotSetHandled() {
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        boolean skipped = registry.skipResolve(null, null, null, resp);

        assertTrue(skipped);
        verify(resp, never()).setHandled();
    }

    @Test
    void skipResolve_voidMethod_handledResponse_doesNotDoubleSet() throws Exception {
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.isHandled()).thenReturn(true);
        Method method = TestController.class.getMethod("voidMethod");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        boolean skipped = registry.skipResolve(null, mapping, null, resp);

        assertTrue(skipped);
        verify(resp, never()).setHandled();
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
        assertNull(registry.getMethodReturnValueContext(null));
    }

    @Test
    void getMethodReturnValueContext_cacheMiss_createsAndReturns() throws Exception {
        registry.initReturnValueResolver();
        Method method = TestController.class.getMethod("handle");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);

        assertNotNull(ctx);
        assertNotNull(ctx.getReturnType());
        assertSame(ctx, mapping.get(ReturnValueResolverRegistry.MAPPING_CACHE_KEY));
    }

    @Test
    void getMethodReturnValueContext_cacheHit_returnsCached() throws Exception {
        registry.initReturnValueResolver();
        Method method = TestController.class.getMethod("handle");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        MethodReturnValueContext first = registry.getMethodReturnValueContext(mapping);
        MethodReturnValueContext second = registry.getMethodReturnValueContext(mapping);

        assertSame(first, second);
    }

    @Test
    void getMethodReturnValueContext_createsAndCaches() throws Exception {
        registry.initReturnValueResolver();
        Method method = TestController.class.getMethod("other");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        MethodReturnValueContext first = registry.getMethodReturnValueContext(mapping);
        MethodReturnValueContext second = registry.getMethodReturnValueContext(mapping);

        assertNotNull(first);
        assertNotNull(mapping.get(ReturnValueResolverRegistry.MAPPING_CACHE_KEY), "首次解析后应写入方法级缓存");
        assertSame(first, second, "二次调用应从缓存返回同一实例");
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
    void resolveReturnValue_voidMethod_setsHandled() throws Exception {
        registry.initReturnValueResolver();
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        Method method = TestController.class.getMethod("voidMethod");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        registry.resolveReturnValue(null, mapping, req, resp);

        verify(resp).setHandled();
    }

    @Test
    void resolveReturnValue_cachedResolverResolves() throws Exception {
        registry.initReturnValueResolver();
        Method method = TestController.class.getMethod("handle");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);
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
        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);
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

    // ==================== 懒缓存测试 ====================

    @Test
    void lazyCache_populatesAfterFirstResolve() throws Exception {
        Method method = TestController.class.getMethod("lazyMethod");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        ReturnValueResolver mockResolver = mock(ReturnValueResolver.class);
        when(mockResolver.supportsReturnValue("test", req, resp)).thenReturn(true);
        registry.addResolver(mockResolver);

        registry.resolveReturnValue("test", mapping, req, resp);

        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);
        assertSame(mockResolver, ctx.getReturnValueResolver());
    }

    @Test
    void lazyCache_hitOnSecondCall() throws Exception {
        Method method = TestController.class.getMethod("lazyMethod");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        ReturnValueResolver mockResolver = mock(ReturnValueResolver.class);
        when(mockResolver.supportsReturnValue(any(), eq(req), eq(resp))).thenReturn(true);
        registry.addResolver(mockResolver);

        // 第一次触发线性扫描 + 缓存
        registry.resolveReturnValue("test", mapping, req, resp);
        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);
        ReturnValueResolver cached = ctx.getReturnValueResolver();

        // 第二次调用
        registry.resolveReturnValue("other", mapping, req, resp);

        assertSame(cached, ctx.getReturnValueResolver());
    }

    @Test
    void lazyCache_asyncType_cachesInnerResolver() throws Exception {
        Method method = TestController.class.getMethod("callableMethod");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        // 先触发 getMethodReturnValueContext 创建 context
        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);

        // 通过反射调用 resolveInnerReturnValueContext
        Method resolveInner = ReturnValueResolverRegistry.class.getDeclaredMethod(
                "resolveInnerReturnValueContext", MethodReturnValueContext.class, MappingHandlerMethod.class);
        resolveInner.setAccessible(true);
        resolveInner.invoke(registry, ctx, mapping);

        assertTrue(ctx.isAsyncType());
        assertNotNull(ctx.getInnerReturnType());
        // callableMethod 返回 Callable<String>，innerType 应为 String
        assertEquals(String.class, ctx.getInnerReturnType().getParameterType());
    }

    @Test
    void addResolver_invalidatesAsyncCache_newAsyncResolverRecognized() throws Exception {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        Object asyncValue = new StringBuilder("async");

        // 初始无异步解析器：isAsyncReturnValue 缓存 FALSE
        assertFalse(registry.isAsyncReturnValue(asyncValue, req, resp));

        // 动态注册一个支持 StringBuilder 的异步解析器
        ReturnValueResolver newAsync = new io.springperf.web.core.retval.resolver.async.BaseAsyncReturnValueResolver() {
            @Override
            public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
                return false;
            }

            @Override
            public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest r, WebServerHttpResponse s) {
                return returnValue instanceof StringBuilder;
            }

            @Override
            public void resolveReturnValue(Object returnValue, MethodParameter returnType,
                                           WebServerHttpRequest r, WebServerHttpResponse s) {
            }
        };
        registry.addResolver(newAsync);

        // addResolver 已失效缓存：新异步解析器应被识别
        assertTrue(registry.isAsyncReturnValue(asyncValue, req, resp));
    }

    @Test
    void lazyCache_nonAsyncType_doesNotSetInnerCache() throws Exception {
        Method method = TestController.class.getMethod("lazyMethod");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);
        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        ReturnValueResolver syncResolver = mock(ReturnValueResolver.class);
        when(syncResolver.supportsReturnValue("test", req, resp)).thenReturn(true);
        registry.addResolver(syncResolver);

        registry.resolveReturnValue("test", mapping, req, resp);

        // 同步类型不应设置 inner 缓存
        assertFalse(ctx.isAsyncType());
        assertNull(ctx.getInnerReturnValueResolver());
    }

    // ==================== effectiveReturnType 测试 ====================

    @Test
    void getMethodReturnValueContext_usesEffectiveReturnType() throws Exception {
        Method method = TestController.class.getMethod("effTypeMethod1");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        // 设置有效返回类型为 Callable<String>
        MethodParameter effectiveType = new MethodParameter(
                TestController.class.getMethod("callableMethod"), -1);
        mapping.setEffectiveReturnType(effectiveType);

        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);

        assertSame(effectiveType, ctx.getReturnType());
    }

    @Test
    void getMethodReturnValueContext_effectiveReturnTypeNull_usesDeclaredType() throws Exception {
        Method method = TestController.class.getMethod("effTypeMethod2");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);

        assertNotNull(ctx.getReturnType());
        assertEquals(String.class, ctx.getReturnType().getParameterType());
    }

    @Test
    void resolveInnerReturnValueContext_usesEffectiveReturnType() throws Exception {
        Method method = TestController.class.getMethod("effTypeMethod3");
        MappingHandlerMethod mapping = new MappingHandlerMethod(new TestController(), method);

        MethodParameter effectiveType = new MethodParameter(
                TestController.class.getMethod("callableMethod"), -1);
        mapping.setEffectiveReturnType(effectiveType);

        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);

        Method resolveInner = ReturnValueResolverRegistry.class.getDeclaredMethod(
                "resolveInnerReturnValueContext", MethodReturnValueContext.class, MappingHandlerMethod.class);
        resolveInner.setAccessible(true);
        resolveInner.invoke(registry, ctx, mapping);

        assertTrue(ctx.isAsyncType());
        assertNotNull(ctx.getInnerReturnType());
        assertEquals(String.class, ctx.getInnerReturnType().getParameterType());
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
        public void voidMethod() {}
        public Callable<String> callableMethod() { return () -> "ok"; }
        public String lazyMethod() { return "lazy"; }
        public String effTypeMethod1() { return "e1"; }
        public String effTypeMethod2() { return "e2"; }
        public String effTypeMethod3() { return "e3"; }
    }
}