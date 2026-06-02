package io.springperf.web.core.arg.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractNamedValueNullableResolverTest {

    @Mock
    WebContext webContext;

    @Mock
    MappingHandlerMethod mappingContext;

    @Mock
    WebDataBinderRegistry webDataBinderRegistry;

    ConversionService conversionService = new DefaultConversionService();

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    private void stubWebContext() {
        doReturn(webDataBinderRegistry).when(webContext).getWebComponent(WebDataBinderRegistry.class);
    }

    private void stubConversionService() {
        when(webDataBinderRegistry.getConversionService(mappingContext)).thenReturn(conversionService);
    }

    // ----- handleNullValue with default -----

    @Test
    void handleNullValue_withDefault_returnsDefault() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueNullableResolver resolver = createResolver(mp, null, false, "default-val");
        Object result = resolver.resolveArgument(request, response);

        assertEquals("default-val", result);
    }

    // ----- handleNullValue required + no default -----

    @Test
    void handleNullValue_requiredNoDefault_throwsException() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueNullableResolver resolver = createResolver(mp, null, true, null);
        assertThrows(IllegalStateException.class,
                () -> resolver.resolveArgument(request, response));
    }

    @Test
    void handleMissingValue_throwsExceptionWithMessage() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueNullableResolver resolver = createResolver(mp, null, true, null);
        try {
            resolver.resolveArgument(request, response);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Missing argument"));
            assertTrue(e.getMessage().contains("String"));
        }
    }

    // ----- handleNullValue not required, no default -----

    @Test
    void handleNullValue_notRequiredNoDefault_booleanReturnsFalse() throws Exception {
        stubWebContext();
        stubConversionService();

        Method method = getClass().getMethod("booleanParam", boolean.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueNullableResolver resolver = createResolver(mp, null, false, null);
        Object result = resolver.resolveArgument(request, response);

        assertEquals(Boolean.FALSE, result);
    }

    @Test
    void handleNullValue_notRequiredNoDefault_nonPrimitiveReturnsNull() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueNullableResolver resolver = createResolver(mp, null, false, null);
        Object result = resolver.resolveArgument(request, response);

        assertNull(result);
    }

    // ----- handleEmptyValue with default -----

    @Test
    void handleEmptyValue_withDefault_returnsDefault() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueNullableResolver resolver = createResolver(mp, "", false, "fallback");
        Object result = resolver.resolveArgument(request, response);

        assertEquals("fallback", result);
    }

    @Test
    void handleEmptyValue_withoutDefault_returnsArg() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueNullableResolver resolver = createResolver(mp, "", false, null);
        Object result = resolver.resolveArgument(request, response);

        assertEquals("", result);
    }

    // ----- required field access (same package resolver) -----

    @Test
    void constructor_setsRequiredField() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueNullableResolver resolver = createResolver(mp, null, true, null);
        assertTrue(resolver.required);
    }

    @Test
    void constructor_setsDefaultValueField() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueNullableResolver resolver = createResolver(mp, null, false, "my-default");
        assertEquals("my-default", resolver.defaultValue);
    }

    // ----- helper -----

    private AbstractNamedValueNullableResolver createResolver(MethodParameter mp, Object resolvedValue,
                                                               boolean required, String defaultValue) {
        return new AbstractNamedValueNullableResolver(mappingContext, mp, webContext,
                "testName", required, defaultValue) {
            @Override
            protected Object resolveByName(WebServerHttpRequest request, WebServerHttpResponse response) {
                return resolvedValue;
            }
        };
    }

    @SuppressWarnings("unused")
    public void stringParam(String name) {}

    @SuppressWarnings("unused")
    public void booleanParam(boolean flag) {}
}
