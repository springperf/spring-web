package io.springperf.web.core.arg.provider;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.arg.resolver.ModelAttributeResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelAttributeResolverProviderTest {

    @Mock
    WebContext webContext;

    @Mock
    WebDataBinderRegistry webDataBinderRegistry;

    @Mock
    MappingHandlerMethod mappingContext;

    @Test
    void supports_withModelAttributeAnnotation_returnsTrue() throws Exception {
        ModelAttributeResolverProvider provider = new ModelAttributeResolverProvider();
        assertTrue(provider.supports(param("annotatedParam", String.class, ModelAttribute.class), null));
    }

    @Test
    void supports_withoutModelAttributeAnnotation_returnsFalse() throws Exception {
        ModelAttributeResolverProvider provider = new ModelAttributeResolverProvider();
        assertFalse(provider.supports(param("stringParam", String.class), null));
    }

    @Test
    void initWithWebContext_initializesWebDataBinderRegistry() {
        when(webContext.getWebComponentWithDefault(eq(WebDataBinderRegistry.class), any()))
                .thenReturn(webDataBinderRegistry);

        ModelAttributeResolverProvider provider = new ModelAttributeResolverProvider();
        provider.initWithWebContext(webContext);

        assertNotNull(provider.webDataBinderRegistry);
    }

    @Test
    void getResolver_returnsModelAttributeResolver() throws Exception {
        when(webContext.getWebComponentWithDefault(eq(WebDataBinderRegistry.class), any()))
                .thenReturn(webDataBinderRegistry);
        when(webDataBinderRegistry.getWebDataBinderFactory(mappingContext)).thenReturn(null);

        ModelAttributeResolverProvider provider = new ModelAttributeResolverProvider();
        provider.initWithWebContext(webContext);

        StaticArgumentResolver resolver = provider.getResolver(
                param("annotatedParam", String.class, ModelAttribute.class), mappingContext, webContext);
        assertNotNull(resolver);
        assertInstanceOf(ModelAttributeResolver.class, resolver);
    }

    @Test
    void isInstanceOfBaseWebComponent() {
        assertInstanceOf(BaseWebComponent.class, new ModelAttributeResolverProvider());
    }

    @SuppressWarnings("unused")
    public void annotatedParam(@ModelAttribute String s) {}

    @SuppressWarnings("unused")
    public void stringParam(String s) {}

    private MethodParameter param(String methodName, Class<?> paramType) throws Exception {
        for (Method m : getClass().getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                return new MethodParameter(m, 0);
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    private MethodParameter param(String methodName, Class<?> paramType, Class<?> annotationClass) throws Exception {
        for (Method m : getClass().getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                return new MethodParameter(m, 0);
            }
        }
        throw new NoSuchMethodException(methodName);
    }
}