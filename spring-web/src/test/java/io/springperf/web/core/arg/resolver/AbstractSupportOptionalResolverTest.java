package io.springperf.web.core.arg.resolver;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AbstractSupportOptionalResolverTest {

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    MappingHandlerMethod mappingContext;

    // ----- Non-optional parameter -----

    @Test
    void resolve_nonOptional_returnsDirectValue() throws Exception {
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        AbstractSupportOptionalResolver resolver = createResolver(mp, "test-value");

        Object result = resolver.resolveArgument(request, response);
        assertEquals("test-value", result);
    }

    @Test
    void resolve_nonOptionalWithNull_returnsNull() throws Exception {
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        AbstractSupportOptionalResolver resolver = createResolver(mp, null);

        Object result = resolver.resolveArgument(request, response);
        assertNull(result);
    }

    // ----- Optional parameter -----

    @Test
    void resolve_optionalWithValue_returnsOptionalOf() throws Exception {
        Method method = getClass().getMethod("optionalParam", Optional.class);
        MethodParameter mp = new MethodParameter(method, 0);
        AbstractSupportOptionalResolver resolver = createResolver(mp, "test-value");

        Object result = resolver.resolveArgument(request, response);
        assertTrue(result instanceof Optional);
        assertTrue(((Optional<String>) result).isPresent());
        assertEquals("test-value", ((Optional<String>) result).get());
    }

    @Test
    void resolve_optionalWithNull_returnsOptionalEmpty() throws Exception {
        Method method = getClass().getMethod("optionalParam", Optional.class);
        MethodParameter mp = new MethodParameter(method, 0);
        AbstractSupportOptionalResolver resolver = createResolver(mp, null);

        Object result = resolver.resolveArgument(request, response);
        assertTrue(result instanceof Optional);
        assertFalse(((Optional<?>) result).isPresent());
    }

    // ----- paramType detection (fields accessible because same package) -----

    @Test
    void nonOptional_returnsCorrectParamType() throws Exception {
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        AbstractSupportOptionalResolver resolver = createResolver(mp, "");

        assertEquals(String.class, resolver.paramType);
        assertFalse(resolver.isOptional);
    }

    @Test
    void optional_returnsWrappedParamType() throws Exception {
        Method method = getClass().getMethod("optionalParam", Optional.class);
        MethodParameter mp = new MethodParameter(method, 0);
        AbstractSupportOptionalResolver resolver = createResolver(mp, "");

        assertEquals(String.class, resolver.paramType);
        assertTrue(resolver.isOptional);
    }

    // ----- parameter & mappingContext -----

    @Test
    void constructor_savesReferences() throws Exception {
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        AbstractSupportOptionalResolver resolver = createResolver(mp, null);

        assertSame(mappingContext, resolver.mappingContext);
        assertSame(mp, resolver.parameter);
    }

    // ----- helper -----

    private AbstractSupportOptionalResolver createResolver(MethodParameter mp, Object value) {
        return new AbstractSupportOptionalResolver(mappingContext, mp) {
            @Override
            protected Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) {
                return value;
            }
        };
    }

    @SuppressWarnings("unused")
    public void stringParam(String s) {}

    @SuppressWarnings("unused")
    public void optionalParam(Optional<String> s) {}
}
