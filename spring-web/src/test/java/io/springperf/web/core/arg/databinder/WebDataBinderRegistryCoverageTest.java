package io.springperf.web.core.arg.databinder;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.validation.Validator;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.method.support.InvocableHandlerMethod;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebDataBinderRegistryCoverageTest {

    @ControllerAdvice
    static class BinderAdvice {
        @InitBinder
        public void globalBinder(WebDataBinder binder) {
        }
    }

    static class AdviceAndLocalController {
        @InitBinder
        public void localBinder(WebDataBinder binder) {
        }

        @SuppressWarnings("unused")
        public void handle() {
        }
    }

    static class FallbackSlot {
        @SuppressWarnings("unused")
        public void handle() {
        }
    }

    @Test
    void initComponentPhase1_discoversAdviceAndFillsDefaults() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(BinderAdvice.class);
            ctx.refresh();
            WebContext wc = realWebContext(ctx);
            WebDataBinderRegistry registry = new WebDataBinderRegistry();
            registry.initWithWebContext(wc);
            registry.initComponentPhase1();

            assertEquals(1, registry.initBinderAdviceCache.size());
            assertNotNull(field(registry, "defaultConversionService"));
            assertNull(field(registry, "defaultValidator"));
            assertNull(field(registry, "messageCodesResolver"));
            assertNull(registry.webDataBinderFactory);
            assertNull(registry.webBindingInitializer);
        }
    }

    @Test
    void getWebDataBinderFactory_collectsAdviceAndLocalInitBinderMethods() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(BinderAdvice.class);
            ctx.refresh();
            WebContext wc = realWebContext(ctx);
            WebDataBinderRegistry registry = new WebDataBinderRegistry();
            registry.initWithWebContext(wc);
            registry.initComponentPhase1();

            MappingHandlerMethod mapping = new MappingHandlerMethod(
                    new AdviceAndLocalController(), AdviceAndLocalController.class.getMethod("handle"));
            List<InvocableHandlerMethod> methods = registry.getInitBinderMethods(mapping);
            assertEquals(2, methods.size());
            for (InvocableHandlerMethod method : methods) {
                assertNotNull(method.getMethod());
            }

            WebDataBinderFactory factory = registry.getWebDataBinderFactory(mapping);
            assertInstanceOf(PerfDataBinderFactory.class, factory);
        }
    }

    @Test
    void getValidators_factoryFails_noInitializer_addsDefaultValidator() throws Exception {
        WebDataBinderRegistry registry = new WebDataBinderRegistry();
        Validator defaultValidator = mock(Validator.class);
        registry.setDefaultValidator(defaultValidator);

        WebDataBinderFactory throwing = mock(WebDataBinderFactory.class);
        when(throwing.createBinder(any(), any(), any())).thenThrow(new NullPointerException("no request"));
        registry.webDataBinderFactory = throwing;

        MappingHandlerMethod mapping = new MappingHandlerMethod(
                new FallbackSlot(), FallbackSlot.class.getMethod("handle"));
        List<Validator> validators = registry.getValidators(mapping);

        assertSame(defaultValidator, validators.get(0));
    }

    private static WebContext realWebContext(AnnotationConfigApplicationContext ctx) {
        DispatcherHandler handler = mock(DispatcherHandler.class);
        when(handler.getComponentName()).thenReturn("DispatcherHandler");
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        WebContext wc = new WebContext(handler, props);
        wc.setApplicationContext(ctx);
        return wc;
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}