package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.resolver.RequestPartResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestPart;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RequestPartResolverProviderTest {

    private final RequestPartResolverProvider provider = new RequestPartResolverProvider();

    @Mock
    WebContext webContext;

    @Test
    void supports_withRequestPartAnnotation_returnsTrue() throws Exception {
        assertTrue(provider.supports(param("annotatedPart", String.class, RequestPart.class), null));
    }

    @Test
    void supports_withoutRequestPartAnnotation_returnsFalse() throws Exception {
        assertFalse(provider.supports(param("stringParam", String.class), null));
    }

    @Test
    void getResolver_returnsRequestPartResolver() throws Exception {
        MethodParameter mp = param("annotatedPart", String.class, RequestPart.class);
        StaticArgumentResolver resolver = provider.getResolver(mp, null, webContext);
        assertNotNull(resolver);
        assertInstanceOf(RequestPartResolver.class, resolver);
    }

    @SuppressWarnings("unused")
    public void annotatedPart(@RequestPart String part) {}

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
        MethodParameter mp = param(methodName, paramType);
        return mp;
    }
}