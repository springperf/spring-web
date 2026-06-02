package io.springperf.web.core.arg.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequestBodyResolverTest {

    @Mock
    WebContext webContext;

    @Mock
    HttpBodyCodecRegistry httpBodyCodecRegistry;

    @Mock
    MappingHandlerMethod mappingContext;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    private void stubCodecRegistry() {
        lenient().when(webContext.getWebComponent(WebDataBinderRegistry.class)).thenReturn(null);
        when(webContext.getWebComponent(HttpBodyCodecRegistry.class)).thenReturn(httpBodyCodecRegistry);
    }

    @Test
    void resolveArgument_required_success() throws Exception {
        stubCodecRegistry();
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        when(httpBodyCodecRegistry.readBody(any(), eq(mp), eq(request), eq(request)))
                .thenReturn("hello");

        RequestBodyResolver resolver = new RequestBodyResolver(webContext, mappingContext, mp, true);
        Object result = resolver.resolveArgument(request, response);

        assertEquals("hello", result);
    }

    @Test
    void resolveArgument_required_failure_throwsException() throws Exception {
        stubCodecRegistry();
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        when(httpBodyCodecRegistry.readBody(any(), eq(mp), eq(request), eq(request)))
                .thenThrow(new RuntimeException("parse error"));

        RequestBodyResolver resolver = new RequestBodyResolver(webContext, mappingContext, mp, true);
        assertThrows(HttpMessageNotReadableException.class,
                () -> resolver.resolveArgument(request, response));
    }

    @Test
    void resolveArgument_notRequired_failure_returnsNull() throws Exception {
        stubCodecRegistry();
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);

        when(httpBodyCodecRegistry.readBody(any(), eq(mp), eq(request), eq(request)))
                .thenThrow(new RuntimeException("parse error"));

        RequestBodyResolver resolver = new RequestBodyResolver(webContext, mappingContext, mp, false);
        Object result = resolver.resolveArgument(request, response);

        assertNull(result);
    }

    @Test
    void implementsStaticArgumentResolver() throws Exception {
        stubCodecRegistry();
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        RequestBodyResolver r = new RequestBodyResolver(webContext, mappingContext, mp, true);
        assertInstanceOf(StaticArgumentResolver.class, r);
    }

    @SuppressWarnings("unused")
    public void stringParam(String body) {}
}