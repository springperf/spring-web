package io.springperf.web.core.arg.provider;

import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.RequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.HttpRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SimpleResolverProvidersTest {

    // ===== RequestResolverProvider =====

    @Test
    void requestResolver_supportsWebServerHttpRequest() throws Exception {
        RequestResolverProvider p = new RequestResolverProvider();
        assertTrue(p.supports(param("requestRest", WebServerHttpRequest.class), null));
    }

    @Test
    void requestResolver_supportsServerHttpRequest() throws Exception {
        RequestResolverProvider p = new RequestResolverProvider();
        assertTrue(p.supports(param("requestServer", ServerHttpRequest.class), null));
    }

    @Test
    void requestResolver_supportsHttpRequest() throws Exception {
        RequestResolverProvider p = new RequestResolverProvider();
        assertTrue(p.supports(param("requestHttp", HttpRequest.class), null));
    }

    @Test
    void requestResolver_supportsHttpInputMessage() throws Exception {
        RequestResolverProvider p = new RequestResolverProvider();
        assertTrue(p.supports(param("requestInput", HttpInputMessage.class), null));
    }

    @Test
    void requestResolver_notSupportsOtherType() throws Exception {
        RequestResolverProvider p = new RequestResolverProvider();
        assertFalse(p.supports(param("stringParam", String.class), null));
    }

    @Test
    void requestResolver_resolverReturnsRequest() throws Exception {
        RequestResolverProvider p = new RequestResolverProvider();
        StaticArgumentResolver r = p.getResolver(null, null, null);
        assertSame(mockRequest, r.resolveArgument(mockRequest, mockResponse));
    }

    // ===== ResponseResolverProvider =====

    @Test
    void responseResolver_supportsWebServerHttpResponse() throws Exception {
        ResponseResolverProvider p = new ResponseResolverProvider();
        assertTrue(p.supports(param("responseRest", WebServerHttpResponse.class), null));
    }

    @Test
    void responseResolver_supportsServerHttpResponse() throws Exception {
        ResponseResolverProvider p = new ResponseResolverProvider();
        assertTrue(p.supports(param("responseServer", ServerHttpResponse.class), null));
    }

    @Test
    void responseResolver_supportsHttpOutputMessage() throws Exception {
        ResponseResolverProvider p = new ResponseResolverProvider();
        assertTrue(p.supports(param("responseOutput", HttpOutputMessage.class), null));
    }

    @Test
    void responseResolver_notSupportsOtherType() throws Exception {
        ResponseResolverProvider p = new ResponseResolverProvider();
        assertFalse(p.supports(param("stringParam", String.class), null));
    }

    @Test
    void responseResolver_resolverReturnsResponse() throws Exception {
        ResponseResolverProvider p = new ResponseResolverProvider();
        StaticArgumentResolver r = p.getResolver(null, null, null);
        assertSame(mockResponse, r.resolveArgument(mockRequest, mockResponse));
    }

    // ===== LocaleResolverProvider =====

    @Test
    void localeResolver_supportsLocale() throws Exception {
        LocaleResolverProvider p = new LocaleResolverProvider();
        assertTrue(p.supports(param("localeParam", Locale.class), null));
    }

    @Test
    void localeResolver_notSupportsOtherType() throws Exception {
        LocaleResolverProvider p = new LocaleResolverProvider();
        assertFalse(p.supports(param("stringParam", String.class), null));
    }

    @Test
    void localeResolver_returnsLocale() throws Exception {
        LocaleContextHolder.setLocale(Locale.UK);
        try {
            LocaleResolverProvider p = new LocaleResolverProvider();
            StaticArgumentResolver r = p.getResolver(param("localeParam", Locale.class), null, null);
            assertEquals(Locale.UK, r.resolveArgument(mockRequest, mockResponse));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    // ===== ErrorsResolverProvider =====

    @Test
    void errorsResolver_supportsErrors() throws Exception {
        ErrorsResolverProvider p = new ErrorsResolverProvider();
        assertTrue(p.supports(param("errorsParam", Errors.class), null));
    }

    @Test
    void errorsResolver_supportsBindingResult() throws Exception {
        ErrorsResolverProvider p = new ErrorsResolverProvider();
        assertTrue(p.supports(param("bindingResultParam", BindingResult.class), null));
    }

    @Test
    void errorsResolver_notSupportsOtherType() throws Exception {
        ErrorsResolverProvider p = new ErrorsResolverProvider();
        assertFalse(p.supports(param("stringParam", String.class), null));
    }

    @Test
    void errorsResolver_existingErrors_returnsErrors() throws Exception {
        ErrorsResolverProvider p = new ErrorsResolverProvider();
        when(mockRequest.getRequestContext()).thenReturn(mockRequestContext);
        when(mockRequestContext.removeAttribute(anyString())).thenReturn(mockErrors);

        StaticArgumentResolver r = p.getResolver(param("errorsParam", Errors.class), null, null);
        assertSame(mockErrors, r.resolveArgument(mockRequest, mockResponse));
    }

    @Test
    void errorsResolver_missingErrors_throws() throws Exception {
        ErrorsResolverProvider p = new ErrorsResolverProvider();
        when(mockRequest.getRequestContext()).thenReturn(mockRequestContext);
        when(mockRequestContext.removeAttribute(anyString())).thenReturn(null);

        StaticArgumentResolver r = p.getResolver(param("errorsParam", Errors.class), null, null);
        assertThrows(IllegalStateException.class, () -> r.resolveArgument(mockRequest, mockResponse));
    }

    // ===== @RequestParam support =====

    @Test
    void requestParamProvider_supportsRequestParam() throws Exception {
        RequestParamResolverProvider p = new RequestParamResolverProvider();
        assertTrue(p.supports(param("annotatedRequestParam", String.class, RequestParam.class), null));
    }

    @Test
    void requestParamProvider_notSupportsWithoutAnnotation() throws Exception {
        RequestParamResolverProvider p = new RequestParamResolverProvider();
        assertFalse(p.supports(param("stringParam", String.class), null));
    }

    // ===== @RequestHeader support =====

    @Test
    void requestHeaderProvider_supportsRequestHeader() throws Exception {
        RequestHeaderResolverProvider p = new RequestHeaderResolverProvider();
        assertTrue(p.supports(param("annotatedRequestHeader", String.class, RequestHeader.class), null));
    }

    // ===== @PathVariable support =====

    @Test
    void pathVariableProvider_supportsPathVariable() throws Exception {
        PathVariableResolverProvider p = new PathVariableResolverProvider();
        assertTrue(p.supports(param("annotatedPathVariable", String.class, PathVariable.class), null));
    }

    // ===== shared mocks via reflection (used as request/response) =====

    @Mock
    WebServerHttpRequest mockRequest;
    @Mock
    WebServerHttpResponse mockResponse;
    @Mock
    RequestContext mockRequestContext;
    @Mock
    Errors mockErrors;

    // ===== helpers =====

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

    // ----- parameter methods for reflection -----

    @SuppressWarnings("unused")
    public void requestRest(WebServerHttpRequest r) {}
    @SuppressWarnings("unused")
    public void requestServer(ServerHttpRequest r) {}
    @SuppressWarnings("unused")
    public void requestHttp(HttpRequest r) {}
    @SuppressWarnings("unused")
    public void requestInput(HttpInputMessage r) {}
    @SuppressWarnings("unused")
    public void responseRest(WebServerHttpResponse r) {}
    @SuppressWarnings("unused")
    public void responseServer(ServerHttpResponse r) {}
    @SuppressWarnings("unused")
    public void responseOutput(HttpOutputMessage r) {}
    @SuppressWarnings("unused")
    public void localeParam(Locale l) {}
    @SuppressWarnings("unused")
    public void errorsParam(Errors e) {}
    @SuppressWarnings("unused")
    public void bindingResultParam(BindingResult b) {}
    @SuppressWarnings("unused")
    public void stringParam(String s) {}
    @SuppressWarnings("unused")
    public void annotatedRequestParam(@RequestParam String s) {}
    @SuppressWarnings("unused")
    public void annotatedRequestHeader(@RequestHeader String s) {}
    @SuppressWarnings("unused")
    public void annotatedPathVariable(@PathVariable String s) {}
}
