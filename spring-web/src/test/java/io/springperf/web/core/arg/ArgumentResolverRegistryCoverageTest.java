package io.springperf.web.core.arg;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.arg.provider.StaticArgumentResolverProvider;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.validation.Errors;
import org.springframework.validation.MessageCodesResolver;
import org.springframework.validation.SmartValidator;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArgumentResolverRegistryCoverageTest {

    static class SmartTarget {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class UnresolvableArg {
        private String x;

        public String getX() {
            return x;
        }

        public void setX(String x) {
            this.x = x;
        }
    }

    public static class NoDefaultCtor {
        @SuppressWarnings("unused")
        public NoDefaultCtor(String required) {
        }
    }

    static class Ctl {
        @SuppressWarnings("unused")
        public void simpleParam(String name) {
        }

        @SuppressWarnings("unused")
        public void complexParam(UnresolvableArg arg) {
        }

        @SuppressWarnings("unused")
        public void modelAttrParam(@ModelAttribute NoDefaultCtor arg) {
        }
    }

    static class RecordingSmartValidator implements SmartValidator {

        final List<Object> hints = new java.util.ArrayList<>();
        private final List<Object> validated = new java.util.ArrayList<>();

        @Override
        public boolean supports(Class<?> clazz) {
            return SmartTarget.class.isAssignableFrom(clazz);
        }

        @Override
        public void validate(Object target, Errors errors) {
            validated.add(target);
        }

        @Override
        public void validate(Object target, Errors errors, Object... validationHints) {
            validated.add(target);
            for (Object hint : validationHints) {
                hints.add(hint);
            }
        }
    }

    @SuppressWarnings("unused")
    public void validatedParam(@Validated SmartTarget target) {
    }

    static class Group1 {}

    @SuppressWarnings("unused")
    public void validatedParamWithHints(@Validated(Group1.class) SmartTarget target) {
    }

    @SuppressWarnings("unused")
    public void plainComplexParam(UnresolvableArg arg) {
    }

    @Test
    void initWithWebContext_wiresBinderRegistryAndProviders() {
        WebContext wc = realWebContext();
        ArgumentResolverRegistry registry = new ArgumentResolverRegistry();
        registry.initWithWebContext(wc);

        assertNotNull(registry.webDataBinderRegistry);
        assertNotNull(registry.requestParamResolverProvider);
        assertNotNull(registry.modelAttributeResolverProvider);
        assertFalse(registry.staticArgumentResolverProviders.isEmpty());
    }

    @Test
    void initComponentPhase3_skipsValidationWhenCheckOff() throws Exception {
        WebContext wc = realWebContext();
        when(wc.getProps().getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true)).thenReturn(false);
        driveLifecycle(wc).initComponentPhase3();
    }

    @Test
    void initComponentPhase3_validatesWhenCheckOn_missingMappingRegistryReturns() throws Exception {
        WebContext wc = realWebContext();
        when(wc.getProps().getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true)).thenReturn(true);
        driveLifecycle(wc).initComponentPhase3();
    }

    @Test
    void validateAllParametersResolvable_emptyMappings_returnsQuietly() {
        WebContext wc = realWebContext();
        ArgumentResolverRegistry registry = new ArgumentResolverRegistry();
        registry.initWithWebContext(wc);

        MappingRegistry mr = registerMappingRegistry(wc, Collections.emptyList());
        assertDoesNotThrow(registry::validateAllParametersResolvable);
        verify(mr).getMappingContextList();
    }

    @Test
    void validateAllParametersResolvable_unresolvableParameter_throws() throws Exception {
        WebContext wc = realWebContext();
        ArgumentResolverRegistry registry = new ArgumentResolverRegistry();
        registry.initWithWebContext(wc);
        registry.modelAttributeResolverProvider = null;

        registerMappingRegistry(wc, Collections.singletonList(mappingContext("complexParam", UnresolvableArg.class)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, registry::validateAllParametersResolvable);
        assertTrue(ex.getMessage().contains("no matching resolver"));
    }

    @Test
    void validateAllParametersResolvable_simpleParam_resolvesAndCompletes() throws Exception {
        WebContext wc = realWebContext();
        ArgumentResolverRegistry registry = new ArgumentResolverRegistry();
        registry.initWithWebContext(wc);
        registry.modelAttributeResolverProvider = null;

        registerMappingRegistry(wc, Collections.singletonList(mappingContext("simpleParam", String.class)));

        assertDoesNotThrow(registry::validateAllParametersResolvable);
    }

    @Test
    void validateAllParametersResolvable_modelAttributeWithoutDefaultCtor_reportsCtorError() throws Exception {
        WebContext wc = realWebContext();
        ArgumentResolverRegistry registry = new ArgumentResolverRegistry();
        registry.initWithWebContext(wc);

        registerMappingRegistry(wc, Collections.singletonList(mappingContext("modelAttrParam", NoDefaultCtor.class)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, registry::validateAllParametersResolvable);
        assertTrue(ex.getMessage().contains("No primary or default constructor"));
    }

    @Test
    void resolveArguments_providerReturnsNullResolver_keepsNullArg() throws Exception {
        ArgumentResolverRegistry registry = new ArgumentResolverRegistry();
        StaticArgumentResolverProvider nullProvider = mock(StaticArgumentResolverProvider.class);
        when(nullProvider.supports(any(), any())).thenReturn(true);
        when(nullProvider.getResolver(any(), any(), any())).thenReturn(null);
        registry.addStaticArgumentResolverProvider(nullProvider);

        Method method = getClass().getMethod("plainComplexParam", UnresolvableArg.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());

        MappingHandlerMethod mapping = mock(MappingHandlerMethod.class);
        when(mapping.createMethodParameters()).thenReturn(new MethodParameter[]{mp});
        when(mapping.get(ArgumentResolverRegistry.MAPPING_CACHE_KEY)).thenReturn(null);

        Object[] args = registry.resolveArguments(mapping,
                mock(WebServerHttpRequest.class), mock(WebServerHttpResponse.class));
        assertNull(args[0]);
        verify(nullProvider).supports(any(), any());
    }

    @Test
    void validateIfApplicable_noSupportingValidator_returnsSilently() throws Exception {
        ArgumentResolverRegistry registry = new ArgumentResolverRegistry();
        WebDataBinderRegistry binder = mock(WebDataBinderRegistry.class);
        when(binder.getValidators(any())).thenReturn(Collections.emptyList());
        registry.webDataBinderRegistry = binder;

        Method method = getClass().getMethod("validatedParam", SmartTarget.class);
        MethodArgContext argCtx = new MethodArgContext(new MethodParameter(method, 0));

        assertDoesNotThrow(() -> registry.validateIfApplicable(new SmartTarget(), argCtx,
                mock(WebServerHttpRequest.class), mock(MappingHandlerMethod.class)));
    }

    @Test
    void validateIfApplicable_validatorDoesNotSupportTarget_returnsSilently() throws Exception {
        ArgumentResolverRegistry registry = new ArgumentResolverRegistry();
        WebDataBinderRegistry binder = mock(WebDataBinderRegistry.class);
        Validator notSupporting = mock(Validator.class);
        when(notSupporting.supports(SmartTarget.class)).thenReturn(false);
        when(binder.getValidators(any())).thenReturn(Collections.singletonList(notSupporting));
        registry.webDataBinderRegistry = binder;

        Method method = getClass().getMethod("validatedParam", SmartTarget.class);
        MethodArgContext argCtx = new MethodArgContext(new MethodParameter(method, 0));

        assertDoesNotThrow(() -> registry.validateIfApplicable(new SmartTarget(), argCtx,
                mock(WebServerHttpRequest.class), mock(MappingHandlerMethod.class)));
        verify(notSupporting).supports(SmartTarget.class);
    }

    @Test
    void validateIfApplicable_smartValidator_usesValidationHints() throws Exception {
        ArgumentResolverRegistry registry = new ArgumentResolverRegistry();
        WebDataBinderRegistry binder = mock(WebDataBinderRegistry.class);
        when(binder.getConversionService(any())).thenReturn(new DefaultFormattingConversionService());
        when(binder.getMessageCodesResolver()).thenReturn(mock(MessageCodesResolver.class));

        RecordingSmartValidator smart = new RecordingSmartValidator();
        when(binder.getValidators(any())).thenReturn(Collections.singletonList(smart));
        registry.webDataBinderRegistry = binder;

        Method method = getClass().getMethod("validatedParamWithHints", SmartTarget.class);
        MethodArgContext argCtx = new MethodArgContext(new MethodParameter(method, 0));

        assertDoesNotThrow(() -> registry.validateIfApplicable(new SmartTarget(), argCtx,
                mock(WebServerHttpRequest.class), mock(MappingHandlerMethod.class)));

        assertEquals(1, smart.hints.size());
        assertSame(Group1.class, smart.hints.get(0));
    }

    @Test
    void getWebDataBinderRegistry_returnsField() {
        ArgumentResolverRegistry registry = new ArgumentResolverRegistry();
        WebDataBinderRegistry binder = mock(WebDataBinderRegistry.class);
        registry.webDataBinderRegistry = binder;
        assertSame(binder, registry.getWebDataBinderRegistry());
    }

    private static WebContext realWebContext() {
        DispatcherHandler handler = mock(DispatcherHandler.class);
        when(handler.getComponentName()).thenReturn("DispatcherHandler");
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.refresh();
        WebContext wc = new WebContext(handler, props);
        wc.setApplicationContext(ctx);
        return wc;
    }

    private static ArgumentResolverRegistry driveLifecycle(WebContext wc) throws Exception {
        ArgumentResolverRegistry registry = new ArgumentResolverRegistry();
        registry.initWithWebContext(wc);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        return registry;
    }

    private static MappingRegistry registerMappingRegistry(WebContext wc, List<PathMappingContext> mappings) {
        MappingRegistry mr = mock(MappingRegistry.class);
        when(mr.getComponentName()).thenReturn("MappingRegistry");
        when(mr.getMappingContextList()).thenReturn(mappings);
        wc.registerWebComponent(mr);
        return mr;
    }

    private static PathMappingContext mappingContext(String methodName, Class<?> paramType) throws Exception {
        Method method = Ctl.class.getMethod(methodName, paramType);
        return new PathMappingContext(new HandlerMethod(new Ctl(), method), Collections.emptyList(), "/" + methodName);
    }
}