package io.springperf.web.core.arg;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.arg.provider.*;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArgumentResolverRegistryTest {

    ArgumentResolverRegistry registry;
    WebContext webContextMock;

    @BeforeEach
    void setUp() throws Exception {
        registry = new ArgumentResolverRegistry();

        // webContext is protected in BaseWebComponent (different package), use reflection
        webContextMock = mock(WebContext.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(any(Class.class))).thenReturn(Collections.emptyMap());
        when(webContextMock.getWebComponent(any(Class.class))).thenReturn(null);
        when(webContextMock.getWebComponentWithDefault(any(Class.class), any())).thenReturn(mock(WebDataBinderRegistry.class));
        when(webContextMock.getCtx()).thenReturn(ctx);

        Field f = registry.getClass().getSuperclass().getSuperclass().getDeclaredField("webContext");
        f.setAccessible(true);
        f.set(registry, webContextMock);

        registry.initStaticArgumentResolverProviders();
        registry.initRuntimeArgumentResolvers();
        registry.webDataBinderRegistry = mock(WebDataBinderRegistry.class);

        registry.requestParamResolverProvider = registry.getWebComponent(RequestParamResolverProvider.class);

        ModelAttributeResolverProvider maProvider = registry.getWebComponent(ModelAttributeResolverProvider.class);
        maProvider.initWithWebContext(webContextMock);
        registry.modelAttributeResolverProvider = maProvider;
    }

    // ----- Initialization -----

    @Test
    void initWithWebContext_initializesProviders() {
        assertFalse(registry.staticArgumentResolverProviders.isEmpty());
    }

    @Test
    void initWithWebContext_initializesRuntimeResolvers() {
        assertNotNull(registry.runtimeArgumentResolvers);
    }

    @Test
    void initWithWebContext_setsWebDataBinderRegistry() {
        assertNotNull(registry.webDataBinderRegistry);
    }

    @Test
    void initWithWebContext_setsRequestParamResolverProvider() {
        assertNotNull(registry.requestParamResolverProvider);
    }

    @Test
    void initWithWebContext_setsModelAttributeResolverProvider() {
        assertNotNull(registry.modelAttributeResolverProvider);
    }

    // ----- static providers initialization -----

    @Test
    void staticProviders_includesAllExpected() {
        assertTrue(registry.staticArgumentResolverProviders.stream()
                .anyMatch(p -> p instanceof RequestBodyResolverProvider));
        assertTrue(registry.staticArgumentResolverProviders.stream()
                .anyMatch(p -> p instanceof RequestHeaderResolverProvider));
        assertTrue(registry.staticArgumentResolverProviders.stream()
                .anyMatch(p -> p instanceof RequestParamResolverProvider));
        assertTrue(registry.staticArgumentResolverProviders.stream()
                .anyMatch(p -> p instanceof PathVariableResolverProvider));
        assertTrue(registry.staticArgumentResolverProviders.stream()
                .anyMatch(p -> p instanceof RequestPartResolverProvider));
        assertTrue(registry.staticArgumentResolverProviders.stream()
                .anyMatch(p -> p instanceof ModelAttributeResolverProvider));
        assertTrue(registry.staticArgumentResolverProviders.stream()
                .anyMatch(p -> p instanceof HttpEntityResolverProvider));
        assertTrue(registry.staticArgumentResolverProviders.stream()
                .anyMatch(p -> p instanceof ErrorsResolverProvider));
        assertTrue(registry.staticArgumentResolverProviders.stream()
                .anyMatch(p -> p instanceof RequestResolverProvider));
        assertTrue(registry.staticArgumentResolverProviders.stream()
                .anyMatch(p -> p instanceof ResponseResolverProvider));
        assertTrue(registry.staticArgumentResolverProviders.stream()
                .anyMatch(p -> p instanceof LocaleResolverProvider));
        assertTrue(registry.staticArgumentResolverProviders.stream()
                .anyMatch(p -> p instanceof MultipartFileResolverProvider));
    }

    // ----- initStaticArgResolverSupport -----

    @Test
    void initStaticArgResolverSupport_requestParam_assignsResolver() throws Exception {
        Method method = getClass().getMethod("requestParamMethod", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());

        MethodArgContext argCtx = new MethodArgContext(mp);
        registry.initStaticArgResolverSupport(mock(MappingHandlerMethod.class), argCtx);

        assertNotNull(argCtx.defaultArgumentResolver);
        assertTrue(argCtx.isStaticArgResolved);
    }

    @Test
    void initStaticArgResolverSupport_unannotatedSimpleProperty_assignsRequestParamResolver() throws Exception {
        Method method = getClass().getMethod("unannotatedSimpleParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());

        MethodArgContext argCtx = new MethodArgContext(mp);
        registry.initStaticArgResolverSupport(mock(MappingHandlerMethod.class), argCtx);

        assertNotNull(argCtx.defaultArgumentResolver);
        assertFalse(argCtx.isStaticArgResolved);
    }

    @Test
    void initStaticArgResolverSupport_unannotatedComplexProperty_assignsModelAttributeResolver() throws Exception {
        Method method = getClass().getMethod("unannotatedComplexParam", ComplexObj.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());

        MethodArgContext argCtx = new MethodArgContext(mp);
        registry.initStaticArgResolverSupport(mock(MappingHandlerMethod.class), argCtx);

        assertNotNull(argCtx.defaultArgumentResolver);
        assertFalse(argCtx.isStaticArgResolved);
    }

    // ----- addRuntimeArgumentResolver -----

    @Test
    void addRuntimeArgumentResolver_addsToList() {
        RuntimeArgumentResolver resolver = mock(RuntimeArgumentResolver.class);
        registry.addRuntimeArgumentResolver(resolver);
        assertTrue(registry.runtimeArgumentResolvers.contains(resolver));
    }

    // ----- addStaticArgumentResolverProvider -----

    @Test
    void addStaticArgumentResolverProvider_addsToList() {
        StaticArgumentResolverProvider provider = mock(StaticArgumentResolverProvider.class);
        registry.addStaticArgumentResolverProvider(provider);
        assertTrue(registry.staticArgumentResolverProviders.contains(provider));
    }

    // ----- resolveArguments with static resolver -----

    @Test
    void resolveArguments_staticResolver_usesIt() throws Exception {
        Method method = getClass().getMethod("requestParamMethod", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());

        MappingHandlerMethod mappingContext = mock(MappingHandlerMethod.class);
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        when(mappingContext.createMethodParameters()).thenReturn(new MethodParameter[]{mp});
        when(mappingContext.get(ArgumentResolverRegistry.MAPPING_CACHE_KEY)).thenReturn(null);
        when(request.getParameterMap()).thenReturn(new org.springframework.util.LinkedMultiValueMap<String, String>() {{
            add("name", "test-value");
        }});

        Object[] args = registry.resolveArguments(mappingContext, request, response);
        assertEquals(1, args.length);
        assertEquals("test-value", args[0]);
    }

    // ----- validateIfApplicable -----

    @Test
    void validateIfApplicable_noValidator_doesNotThrow() throws Exception {
        Method method = getClass().getMethod("requestParamMethod", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
        MethodArgContext argCtx = new MethodArgContext(mp);

        assertDoesNotThrow(() ->
                registry.validateIfApplicable("test", argCtx, mock(WebServerHttpRequest.class), mock(MappingHandlerMethod.class)));
    }

    // ----- helper methods -----

    @SuppressWarnings("unused")
    public void requestParamMethod(@RequestParam("name") String name) {}

    @SuppressWarnings("unused")
    public void unannotatedSimpleParam(String name) {}

    @SuppressWarnings("unused")
    public void unannotatedComplexParam(ComplexObj obj) {}

    static class ComplexObj {
        private String field;
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
    }
}
