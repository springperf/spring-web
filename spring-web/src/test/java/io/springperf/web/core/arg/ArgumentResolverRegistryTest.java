package io.springperf.web.core.arg;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.arg.provider.*;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.MethodArgumentNotValidException;

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
        registry.webDataBinderRegistry = mock(WebDataBinderRegistry.class);
        // validateIfApplicable/createBindingResult 依赖 conversion service，提供默认值
        when(registry.webDataBinderRegistry.getConversionService(any()))
                .thenReturn(new org.springframework.format.support.DefaultFormattingConversionService());

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

    @Test
    void validateIfApplicable_withValidAnnotation_validatorErrors_throws() throws Exception {
        // 覆盖 validateIfApplicable 的完整校验分支：@Validated + validator 报错 +
        // 无相邻 BindingResult（ArgumentResolverRegistry.java:181-211）→ 抛 MethodArgumentNotValidException
        Method method = getClass().getMethod("validatedReqBody", ValidTarget.class);
        MethodParameter mp = new MethodParameter(method, 0);
        MethodArgContext argCtx = new MethodArgContext(mp);
        assertTrue(argCtx.isHaveValidateAnnotation());

        Validator validator = mock(Validator.class);
        when(validator.supports(ValidTarget.class)).thenReturn(true);
        doAnswer(invocation -> {
            org.springframework.validation.Errors errors = invocation.getArgument(1);
            errors.rejectValue("name", "required", "name is required");
            return null;
        }).when(validator).validate(any(), any());
        when(registry.webDataBinderRegistry.getValidators(any())).thenReturn(Collections.singletonList(validator));

        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        io.springperf.web.http.RequestContext requestContext = mock(io.springperf.web.http.RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);

        assertThrows(MethodArgumentNotValidException.class,
                () -> registry.validateIfApplicable(new ValidTarget(), argCtx, request, mock(MappingHandlerMethod.class)));
        // 校验失败时不应重复创建 BindingResult 写入请求属性（无相邻 BindingResult 参数，走抛异常）
        verify(requestContext, never()).setAttribute(any(io.springperf.web.http.RequestAttribute.class), any());
        verify(requestContext, never()).setAttribute(any(String.class), any());
    }

    @Test
    void validateIfApplicable_withValidAnnotation_nextBindingResult_doesNotThrow() throws Exception {
        // 相邻参数为 BindingResult 时：@Validated + validator 报错仍不抛，
        // 且 BindingResult 已写入请求属性供后续参数使用（ArgumentResolverRegistry.java:183-186）
        Method method = getClass().getMethod("validatedWithBindingResult", ValidTarget.class, BindingResult.class);
        MethodParameter mp = new MethodParameter(method, 0);
        MethodArgContext argCtx = new MethodArgContext(mp);
        assertTrue(argCtx.isHaveValidateAnnotation());
        assertTrue(argCtx.isHasBindingResult());

        Validator validator = mock(Validator.class);
        when(validator.supports(ValidTarget.class)).thenReturn(true);
        doAnswer(invocation -> {
            org.springframework.validation.Errors errors = invocation.getArgument(1);
            errors.rejectValue("name", "required", "name is required");
            return null;
        }).when(validator).validate(any(), any());
        when(registry.webDataBinderRegistry.getValidators(any())).thenReturn(Collections.singletonList(validator));

        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        io.springperf.web.http.RequestContext requestContext = mock(io.springperf.web.http.RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);

        assertDoesNotThrow(() ->
                registry.validateIfApplicable(new ValidTarget(), argCtx, request, mock(MappingHandlerMethod.class)));
        verify(requestContext).setAttribute(eq(argCtx.getBindingResultAttrKey()),
                any(org.springframework.validation.BeanPropertyBindingResult.class));
    }

    // ----- D3: 无默认构造器 @ModelAttribute 启动即失败（fail-fast） -----

    @Test
    void validateAllParametersResolvable_modelAttributeWithoutDefaultCtor_throws() throws Exception {
        MappingRegistry mappingRegistry = mock(MappingRegistry.class);
        when(webContextMock.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);

        NoDefaultCtorController bean = new NoDefaultCtorController();
        Method method = NoDefaultCtorController.class.getMethod("modelAttrMethod", NoDefaultCtor.class);
        HandlerMethod handlerMethod = new HandlerMethod(bean, method);
        PathMappingContext ctx = new PathMappingContext(handlerMethod, Collections.<Matcher>emptyList(), "/model");
        when(mappingRegistry.getMappingContextList()).thenReturn(Collections.singletonList(ctx));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.validateAllParametersResolvable());
        assertTrue(ex.getMessage().contains("No primary or default constructor"),
                "启动校验必须暴露无默认构造器问题，而非等到首个请求 500");
    }

    // ----- helper methods -----

    @SuppressWarnings("unused")
    public void requestParamMethod(@RequestParam("name") String name) {}

    @SuppressWarnings("unused")
    public void unannotatedSimpleParam(String name) {}

    @SuppressWarnings("unused")
    public void unannotatedComplexParam(ComplexObj obj) {}

    @SuppressWarnings("unused")
    public void validatedReqBody(@Validated ValidTarget target) {}

    @SuppressWarnings("unused")
    public void validatedWithBindingResult(@Validated ValidTarget target, BindingResult result) {}

    @SuppressWarnings("unused")
    public static class ValidTarget {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    static class ComplexObj {
        private String field;
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
    }

    @Controller
    static class NoDefaultCtorController {
        @SuppressWarnings("unused")
        public void modelAttrMethod(@ModelAttribute("obj") NoDefaultCtor obj) {}
    }

    public static class NoDefaultCtor {
        @SuppressWarnings("unused")
        public NoDefaultCtor(String required) {}
    }
}
