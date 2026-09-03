package io.springperf.web.core.retval;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.retval.resolver.async.BaseAsyncReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReturnValueResolverRegistryCoverageTest {

    static class TestController {
        public String plain() {
            return "plain";
        }

        public Optional<String> optMethod() {
            return Optional.empty();
        }

        public Optional<Integer> optMethod2() {
            return Optional.empty();
        }

        public Callable<String> delayed() {
            return () -> "x";
        }

        public Callable<String> delayed2() {
            return () -> "y";
        }

        public Callable<Object> delayedObject() {
            return () -> new Object();
        }
    }

    private ReturnValueResolverRegistry registry;
    private WebServerHttpRequest req;
    private WebServerHttpResponse resp;

    @BeforeEach
    void setUp() {
        registry = new ReturnValueResolverRegistry();
        req = mock(WebServerHttpRequest.class);
        resp = mock(WebServerHttpResponse.class);
    }

    @Test
    void initWithWebContext_registersDefaultResolvers() throws Exception {
        WebContext webContext = mock(WebContext.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(webContext.getCtx()).thenReturn(applicationContext);
        when(applicationContext.getBeansOfType(any(Class.class))).thenReturn(Collections.emptyMap());

        ReturnValueResolverRegistry local = new ReturnValueResolverRegistry();
        local.initWithWebContext(webContext);

        assertFalse(getResolvers(local).isEmpty());
    }

    @Test
    void resolve_emptyOptional_marksHandled() throws Exception {
        registry.resolveReturnValue(Optional.empty(), mapping("optMethod"), req, resp);
        verify(resp).setHandled();
    }

    @Test
    void resolve_optionalWithCachedInnerResolver_usesInlineResolver() throws Exception {
        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping("optMethod"));
        assertTrue(ctx.isOptionalType());
        assertNotNull(ctx.getOptionalInnerReturnType());
        ReturnValueResolver inner = mock(ReturnValueResolver.class);
        when(inner.supportsReturnValue("x", req, resp)).thenReturn(true);
        ctx.setOptionalInnerReturnValueResolver(inner);

        registry.resolveReturnValue(Optional.of("x"), mapping("optMethod"), req, resp);

        verify(inner).resolveReturnValue(eq("x"), eq(ctx.getOptionalInnerReturnType()), eq(req), eq(resp));
        verify(resp).setHandled();
    }

    @Test
    void resolve_optionalWithLinearMatch_cachesInnerResolver() throws Exception {
        ReturnValueResolver linear = mock(ReturnValueResolver.class);
        when(linear.supportsReturnValue("y", req, resp)).thenReturn(true);
        registry.addResolver(linear);
        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping("optMethod2"));

        registry.resolveReturnValue(Optional.of("y"), mapping("optMethod2"), req, resp);

        assertSame(linear, ctx.getOptionalInnerReturnValueResolver());
        verify(resp).setHandled();
    }

    @Test
    void resolve_asyncValue_primesInnerContextViaLinearScan() throws Exception {
        RecordingAsyncResolver asyncResolver = new RecordingAsyncResolver();
        registry.addResolver(asyncResolver);
        MappingHandlerMethod mapping = mapping("delayed");
        Callable<String> callable = () -> "x";

        registry.resolveReturnValue(callable, mapping, req, resp);

        assertTrue(asyncResolver.lastValue == callable);
        verify(resp).setHandled();

        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);
        assertTrue(ctx.isAsyncType());
        assertSame(asyncResolver, ctx.getInnerReturnValueResolver());
        assertNotNull(ctx.getInnerReturnType());
        assertEquals(String.class, ctx.getInnerReturnType().getGenericParameterType());
        assertEquals(String.class, ctx.getInnerReturnType().getParameterType());
    }

    @Test
    void resolve_asyncDispatchResult_hitsFastPath2InlineResolver() throws Exception {
        RecordingAsyncResolver asyncResolver = new RecordingAsyncResolver();
        registry.addResolver(asyncResolver);
        MappingHandlerMethod mapping = mapping("delayed2");
        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);
        registry.resolveInnerReturnValueContext(ctx, mapping);
        assertTrue(ctx.isAsyncType());
        assertSame(asyncResolver, ctx.getInnerReturnValueResolver());

        clearInvocations(resp);
        registry.resolveReturnValue("x", mapping, req, resp);

        assertEquals("x", asyncResolver.lastValue);
        assertEquals(String.class, asyncResolver.lastReturnType.getGenericParameterType());
        verify(resp).setHandled();
    }

    @Test
    void isAsyncReturnValue_unsupportedValue_cachesFalse() {
        RejectingAsyncResolver rejecting = new RejectingAsyncResolver();
        registry.addResolver(rejecting);

        assertFalse(registry.isAsyncReturnValue("zzz", req, resp));
        assertFalse(registry.isAsyncReturnValue("zzz", req, resp));
    }

    @Test
    void resolve_asyncInnerGenericIsObject_skipsInnerCaching() throws Exception {
        ReturnValueResolver asyncResolver = new RecordingAsyncResolver();
        registry.addResolver(asyncResolver);
        MappingHandlerMethod mapping = mapping("delayedObject");
        Callable<Object> callable = () -> new Object();

        registry.resolveReturnValue(callable, mapping, req, resp);

        MethodReturnValueContext ctx = registry.getMethodReturnValueContext(mapping);
        assertTrue(ctx.isAsyncType());
        assertNull(ctx.getInnerReturnType());
        assertNull(ctx.getInnerReturnValueResolver());
        verify(resp).setHandled();
    }

    @Test
    void resolve_noResolverSupports_returnsWithoutHandling() throws Exception {
        ReturnValueResolver no = mock(ReturnValueResolver.class);
        when(no.supportsReturnValue(any(), any(), any())).thenReturn(false);
        registry.addResolver(no);
        when(resp.isHandled()).thenReturn(false);

        registry.resolveReturnValue("nope", mapping("plain"), req, resp);

        verify(resp, never()).setHandled();
    }

    private MappingHandlerMethod mapping(String methodName) throws Exception {
        return new MappingHandlerMethod(new TestController(), TestController.class.getMethod(methodName));
    }

    @SuppressWarnings("unchecked")
    private static List<ReturnValueResolver> getResolvers(ReturnValueResolverRegistry target) throws Exception {
        Field field = ReturnValueResolverRegistry.class.getDeclaredField("resolvers");
        field.setAccessible(true);
        return (List<ReturnValueResolver>) field.get(target);
    }

    static class RecordingAsyncResolver extends BaseAsyncReturnValueResolver {
        Object lastValue;
        MethodParameter lastReturnType;

        @Override
        public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
            return true;
        }

        @Override
        public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest r, WebServerHttpResponse s) {
            return returnValue instanceof Callable || returnValue instanceof String;
        }

        @Override
        public void resolveReturnValue(Object returnValue, MethodParameter returnType,
                                       WebServerHttpRequest r, WebServerHttpResponse s) {
            lastValue = returnValue;
            lastReturnType = returnType;
        }
    }

    static class RejectingAsyncResolver extends BaseAsyncReturnValueResolver {
        @Override
        public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
            return false;
        }

        @Override
        public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest r, WebServerHttpResponse s) {
            return false;
        }

        @Override
        public void resolveReturnValue(Object returnValue, MethodParameter returnType,
                                       WebServerHttpRequest r, WebServerHttpResponse s) {
        }
    }
}