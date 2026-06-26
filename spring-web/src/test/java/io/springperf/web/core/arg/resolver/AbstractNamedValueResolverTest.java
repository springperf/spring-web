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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbstractNamedValueResolverTest {

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

    // ----- resolveByName delegation -----

    @Test
    void resolveByName_isCalled() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());

        AbstractNamedValueResolver resolver = createResolver(mp, "resolved-value");
        Object result = resolver.resolveArgument(request, response);

        assertEquals("resolved-value", result);
    }

    // ----- null handling for primitives -----

    @Test
    void handleNullValue_booleanPrimitive_returnsFalse() throws Exception {
        stubWebContext();
        stubConversionService();

        Method method = getClass().getMethod("booleanParam", boolean.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueResolver resolver = createResolver(mp, null);
        Object result = resolver.resolveArgument(request, response);

        assertEquals(Boolean.FALSE, result);
    }

    @Test
    void handleNullValue_nonBooleanPrimitive_throwsException() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("intParam", int.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueResolver resolver = createResolver(mp, null);
        assertThrows(IllegalStateException.class,
                () -> resolver.resolveArgument(request, response));
    }

    @Test
    void handleNullValue_nonPrimitive_returnsNull() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueResolver resolver = createResolver(mp, null);
        Object result = resolver.resolveArgument(request, response);

        assertNull(result);
    }

    // ----- type conversion -----

    @Test
    void convert_sameType_returnsDirectly() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueResolver resolver = createResolver(mp, "hello");
        Object result = resolver.resolveArgument(request, response);

        assertEquals("hello", result);
    }

    @Test
    void convert_differentType_convertsUsingConversionService() throws Exception {
        stubWebContext();
        stubConversionService();

        Method method = getClass().getMethod("intParam", int.class);
        MethodParameter mp = new MethodParameter(method, 0);

        AbstractNamedValueResolver resolver = createResolver(mp, "42");
        Object result = resolver.resolveArgument(request, response);

        assertEquals(42, result);
    }

    // ----- name field access (same package resolver) -----

    @Test
    void constructor_setsName() throws Exception {
        stubWebContext();

        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());

        AbstractNamedValueResolver resolver = createResolver(mp, "");

        assertEquals("name", resolver.name);
    }

    // ----- helper -----

    private AbstractNamedValueResolver createResolver(MethodParameter mp, Object resolvedValue) {
        return new AbstractNamedValueResolver(webContext, mappingContext, mp, new Class[0]) {
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

    @SuppressWarnings("unused")
    public void intParam(int count) {}
}
