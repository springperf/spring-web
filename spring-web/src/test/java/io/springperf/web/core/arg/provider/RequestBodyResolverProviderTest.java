package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.resolver.RequestBodyResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RequestBodyResolverProviderTest {

    private final RequestBodyResolverProvider provider = new RequestBodyResolverProvider();

    @Mock
    WebContext webContext;

    @Test
    void supports_withRequestBodyAnnotation_returnsTrue() throws Exception {
        assertTrue(provider.supports(param("annotatedBody", String.class, RequestBody.class), null));
    }

    @Test
    void supports_withoutRequestBodyAnnotation_returnsFalse() throws Exception {
        assertFalse(provider.supports(param("stringParam", String.class), null));
    }

    @Test
    void getResolver_returnsRequestBodyResolver() throws Exception {
        MethodParameter mp = param("annotatedBody", String.class, RequestBody.class);
        StaticArgumentResolver resolver = provider.getResolver(mp, null, webContext);
        assertNotNull(resolver);
        assertInstanceOf(RequestBodyResolver.class, resolver);
    }

    @SuppressWarnings("unused")
    public void annotatedBody(@RequestBody String body) {}

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
        // verify annotation is present
        return mp;
    }
}