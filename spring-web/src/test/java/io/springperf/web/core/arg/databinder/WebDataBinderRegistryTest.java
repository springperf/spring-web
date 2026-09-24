package io.springperf.web.core.arg.databinder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionService;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.validation.Validator;
import org.springframework.web.bind.support.WebBindingInitializer;
import org.springframework.web.bind.support.WebDataBinderFactory;

import io.springperf.web.core.mapping.MappingHandlerMethod;

class WebDataBinderRegistryTest {

    private MappingHandlerMethod createHandlerMethod() {
        try {
            return new MappingHandlerMethod(new Object(), Object.class.getDeclaredMethod("toString"));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void getMessageCodesResolver_defaultIsNull() {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        assertNull(registry.getMessageCodesResolver());
    }

    @Test
    void getWebDataBinderFactory_returnsPerfDataBinderFactory() {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        MappingHandlerMethod handlerMethod = createHandlerMethod();

        WebDataBinderFactory factory = registry.getWebDataBinderFactory(handlerMethod);

        assertNotNull(factory);
        assertInstanceOf(PerfDataBinderFactory.class, factory);
    }

    @Test
    void getWebDataBinderFactory_cachesResult() {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        MappingHandlerMethod handlerMethod = createHandlerMethod();

        WebDataBinderFactory factory1 = registry.getWebDataBinderFactory(handlerMethod);
        WebDataBinderFactory factory2 = registry.getWebDataBinderFactory(handlerMethod);

        assertSame(factory1, factory2);
    }

    @Test
    void getConversionService_usesDefaultWhenFactoryNotAvailable() throws Exception {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        MappingHandlerMethod handlerMethod = createHandlerMethod(SlotA.class);

        ConversionService testService = new DefaultFormattingConversionService();
        setField(registry, "defaultConversionService", testService);

        ConversionService result = registry.getConversionService(handlerMethod);
        assertSame(testService, result);
    }

    @Test
    void getConversionService_cachesResult() throws Exception {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        MappingHandlerMethod handlerMethod = createHandlerMethod(SlotB.class);

        ConversionService testService = new DefaultFormattingConversionService();
        setField(registry, "defaultConversionService", testService);

        ConversionService result1 = registry.getConversionService(handlerMethod);
        ConversionService result2 = registry.getConversionService(handlerMethod);

        assertSame(result1, result2);
    }

    @Test
    void getValidators_returnsEmptyListWhenNoDefaultValidator() throws Exception {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        MappingHandlerMethod handlerMethod = createHandlerMethod(SlotC.class);

        List<Validator> validators = registry.getValidators(handlerMethod);

        assertTrue(validators.isEmpty());
    }

    @Test
    void getValidators_cachesResult() throws Exception {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        MappingHandlerMethod handlerMethod = createHandlerMethod(SlotD.class);

        List<Validator> validators1 = registry.getValidators(handlerMethod);
        List<Validator> validators2 = registry.getValidators(handlerMethod);

        assertSame(validators1, validators2);
    }

    // ----- D6: createBinder(null) 失败时兜底 WebBindingInitializer binder -----

    @Test
    void getConversionService_factoryFails_fallsBackToWebBindingInitializer() throws Exception {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        MappingHandlerMethod handlerMethod = createHandlerMethod(FallbackA.class);

        WebDataBinderFactory factory = mock(WebDataBinderFactory.class);
        when(factory.createBinder(any(), any(), any())).thenThrow(new NullPointerException("no request"));
        setField(registry, "webDataBinderFactory", factory);

        ConversionService testService = new DefaultFormattingConversionService();
        setField(registry, "webBindingInitializer",
                (WebBindingInitializer) binder -> binder.setConversionService(testService));

        ConversionService result = registry.getConversionService(handlerMethod);

        assertSame(testService, result);
    }

    @Test
    void getConversionService_factoryFails_noInitializer_fallsBackToDefault() throws Exception {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        MappingHandlerMethod handlerMethod = createHandlerMethod(FallbackB.class);

        WebDataBinderFactory factory = mock(WebDataBinderFactory.class);
        when(factory.createBinder(any(), any(), any())).thenThrow(new NullPointerException("no request"));
        setField(registry, "webDataBinderFactory", factory);

        ConversionService testService = new DefaultFormattingConversionService();
        setField(registry, "defaultConversionService", testService);

        ConversionService result = registry.getConversionService(handlerMethod);

        assertSame(testService, result);
    }

    @Test
    void getValidators_factoryFails_fallsBackToWebBindingInitializer() throws Exception {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        MappingHandlerMethod handlerMethod = createHandlerMethod(FallbackC.class);

        WebDataBinderFactory factory = mock(WebDataBinderFactory.class);
        when(factory.createBinder(any(), any(), any())).thenThrow(new NullPointerException("no request"));
        setField(registry, "webDataBinderFactory", factory);

        Validator testValidator = mock(Validator.class);
        setField(registry, "webBindingInitializer",
                (WebBindingInitializer) binder -> binder.addValidators(testValidator));

        List<Validator> validators = registry.getValidators(handlerMethod);

        assertTrue(validators.contains(testValidator));
    }

    private static MappingHandlerMethod createHandlerMethod(Class<?> userClass) throws Exception {
        return new MappingHandlerMethod(userClass.getDeclaredConstructor().newInstance(),
                userClass.getDeclaredMethod("handle"));
    }

    // D6 测试用独立 userClass：MappingCacheKey.createClassCacheKey 的缓存按 userClass 静态共享，
    // 若都用 Object.class 会被其他测试预填的 CONVERSION_SERVICE_KEY 污染。
    private static final class FallbackA {
        @SuppressWarnings("unused")
        public void handle() {
        }
    }

    private static final class FallbackB {
        @SuppressWarnings("unused")
        public void handle() {
        }
    }

    private static final class FallbackC {
        @SuppressWarnings("unused")
        public void handle() {
        }
    }

    // ==================== spring.mvc.message-codes-resolver-format ====================

    @Test
    void createDefaultMessageCodesResolver_default_isPrefixFormat() throws Exception {
        WebDataBinderRegistry registry = registryWithProps(
                io.springperf.web.context.PropertiesConstant.MVC_MESSAGE_CODES_RESOLVER_FORMAT_DEFAULT);
        org.springframework.validation.MessageCodesResolver resolver = registry.createDefaultMessageCodesResolver();
        assertNotNull(resolver);
        String[] codes = resolver.resolveMessageCodes("NotBlank", "target", "name", String.class);
        // prefix 格式：NotBlank.target.name（错误码在前）
        assertEquals("NotBlank.target.name", codes[0]);
    }

    @Test
    void createDefaultMessageCodesResolver_postfixFormat() throws Exception {
        WebDataBinderRegistry registry = registryWithProps("postfix_error_code");
        org.springframework.validation.MessageCodesResolver resolver = registry.createDefaultMessageCodesResolver();
        String[] codes = resolver.resolveMessageCodes("NotBlank", "target", "name", String.class);
        // postfix 格式：target.name.NotBlank（错误码在后）
        assertEquals("target.name.NotBlank", codes[0]);
    }

    @Test
    void createDefaultMessageCodesResolver_invalidFormat_fallsBackToPrefix() throws Exception {
        WebDataBinderRegistry registry = registryWithProps("bogus");
        org.springframework.validation.MessageCodesResolver resolver = registry.createDefaultMessageCodesResolver();
        String[] codes = resolver.resolveMessageCodes("NotBlank", "target", "name", String.class);
        assertEquals("NotBlank.target.name", codes[0]);
    }

    private static WebDataBinderRegistry registryWithProps(String resolverFormat) throws Exception {
        io.springperf.web.context.ApplicationProperties props = mock(
                io.springperf.web.context.ApplicationProperties.class);
        when(props.get(io.springperf.web.context.PropertiesConstant.MVC_MESSAGE_CODES_RESOLVER_FORMAT,
                io.springperf.web.context.PropertiesConstant.MVC_MESSAGE_CODES_RESOLVER_FORMAT_DEFAULT))
                        .thenReturn(resolverFormat);
        io.springperf.web.context.WebContext ctx = mock(io.springperf.web.context.WebContext.class);
        when(ctx.getProps()).thenReturn(props);
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        registry.initWithWebContext(ctx);
        return registry;
    }

    // 同样按 userClass 静态共享缓存，getConversionService/getValidators 的用例各自使用独立
    // userClass 槽位，避免测试顺序导致缓存串扰。
    private static final class SlotA {
        @SuppressWarnings("unused")
        public void handle() {
        }
    }

    private static final class SlotB {
        @SuppressWarnings("unused")
        public void handle() {
        }
    }

    private static final class SlotC {
        @SuppressWarnings("unused")
        public void handle() {
        }
    }

    private static final class SlotD {
        @SuppressWarnings("unused")
        public void handle() {
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
