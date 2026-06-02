package io.springperf.web.core.arg.databinder;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionService;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.validation.Validator;
import org.springframework.web.bind.support.WebDataBinderFactory;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        MappingHandlerMethod handlerMethod = createHandlerMethod();

        ConversionService testService = new DefaultFormattingConversionService();
        setField(registry, "defaultConversionService", testService);

        ConversionService result = registry.getConversionService(handlerMethod);
        assertSame(testService, result);
    }

    @Test
    void getConversionService_cachesResult() throws Exception {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        MappingHandlerMethod handlerMethod = createHandlerMethod();

        ConversionService testService = new DefaultFormattingConversionService();
        setField(registry, "defaultConversionService", testService);

        ConversionService result1 = registry.getConversionService(handlerMethod);
        ConversionService result2 = registry.getConversionService(handlerMethod);

        assertSame(result1, result2);
    }

    @Test
    void getValidators_returnsEmptyListWhenNoDefaultValidator() {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        MappingHandlerMethod handlerMethod = createHandlerMethod();

        List<Validator> validators = registry.getValidators(handlerMethod);

        assertNotNull(validators);
    }

    @Test
    void getValidators_cachesResult() {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        MappingHandlerMethod handlerMethod = createHandlerMethod();

        List<Validator> validators1 = registry.getValidators(handlerMethod);
        List<Validator> validators2 = registry.getValidators(handlerMethod);

        assertSame(validators1, validators2);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
