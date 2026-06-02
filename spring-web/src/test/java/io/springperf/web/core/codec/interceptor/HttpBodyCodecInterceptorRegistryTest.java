package io.springperf.web.core.codec.interceptor;

import io.springperf.web.core.codec.HttpBodyConverter;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpBodyCodecInterceptorRegistryTest {

    HttpBodyCodecInterceptorRegistry registry;

    HttpBodyCodecInterceptor interceptor1;
    HttpBodyCodecInterceptor interceptor2;

    @Mock
    WebServerHttpRequest request;

    @Mock
    RequestContext requestContext;

    @Mock
    MethodParameter parameter;

    @Mock
    HttpBodyConverter converter;

    @Mock
    HttpInputMessage inputMessage;

    @Mock
    WebServerHttpResponse response;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        registry = new HttpBodyCodecInterceptorRegistry();
        interceptor1 = mock(HttpBodyCodecInterceptor.class);
        interceptor2 = mock(HttpBodyCodecInterceptor.class);
    }

    private void addInterceptor(HttpBodyCodecInterceptor interceptor) {
        registry.codecInterceptors.add(new WebComponentControllerAdviceBean<>("interceptor", interceptor));
    }

    private void stubPathMapping() {
        lenient().when(request.getRequestContext()).thenReturn(requestContext);
    }

    private void stubSupportRead(MethodParameter param, Type targetType, boolean support) {
        when(interceptor1.supportBodyRead(param, targetType, converter)).thenReturn(support);
    }

    // ---- realGetCodecInterceptor ----

    @Test
    void realGetCodecInterceptor_noInterceptors_returnsEmpty() {
        MethodParameter param = mock(MethodParameter.class);

        HttpBodyCodecInterceptor[] result = registry.realGetCodecInterceptor(param);

        assertEquals(0, result.length);
    }

    @Test
    void realGetCodecInterceptor_applicableInterceptor_returnsIt() {
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);

        HttpBodyCodecInterceptor[] result = registry.realGetCodecInterceptor(param);

        assertEquals(1, result.length);
        assertSame(interceptor1, result[0]);
    }

    @Test
    void realGetCodecInterceptor_multipleApplicable_returnsAll() {
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);
        addInterceptor(interceptor2);

        HttpBodyCodecInterceptor[] result = registry.realGetCodecInterceptor(param);

        assertEquals(2, result.length);
    }

    // ---- getCodecInterceptor (without PathMappingContext) ----

    @Test
    void getCodecInterceptor_withoutMappingContext_delegatesToReal() {
        stubPathMapping();
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);

        HttpBodyCodecInterceptor[] result = registry.getCodecInterceptor(request, param);

        assertEquals(0, result.length);
    }

    @Test
    void getCodecInterceptor_withoutMappingContext_usesInterceptors() {
        stubPathMapping();
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);

        HttpBodyCodecInterceptor[] result = registry.getCodecInterceptor(request, param);

        assertEquals(1, result.length);
        assertSame(interceptor1, result[0]);
    }

    // ---- beforeBodyRead ----

    @Test
    void beforeBodyRead_applicableInterceptor_applies() throws IOException {
        stubPathMapping();
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);
        stubSupportRead(param, String.class, true);
        when(interceptor1.beforeBodyRead(inputMessage, param, String.class, converter)).thenReturn(inputMessage);

        HttpInputMessage result = registry.beforeBodyRead(request, inputMessage, param, String.class, converter);

        assertNotNull(result);
        verify(interceptor1).supportBodyRead(param, String.class, converter);
        verify(interceptor1).beforeBodyRead(inputMessage, param, String.class, converter);
    }

    @Test
    void beforeBodyRead_interceptorNotSupport_skips() throws IOException {
        stubPathMapping();
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);
        stubSupportRead(param, String.class, false);

        registry.beforeBodyRead(request, inputMessage, param, String.class, converter);

        verify(interceptor1, never()).beforeBodyRead(any(), any(), any(), any());
    }

    @Test
    void beforeBodyRead_multipleInterceptors_bothApplied() throws IOException {
        stubPathMapping();
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);
        addInterceptor(interceptor2);
        stubSupportRead(param, String.class, true);
        when(interceptor2.supportBodyRead(param, String.class, converter)).thenReturn(true);
        when(interceptor1.beforeBodyRead(inputMessage, param, String.class, converter)).thenReturn(inputMessage);

        registry.beforeBodyRead(request, inputMessage, param, String.class, converter);

        verify(interceptor1).beforeBodyRead(inputMessage, param, String.class, converter);
        verify(interceptor2).beforeBodyRead(inputMessage, param, String.class, converter);
    }

    // ---- afterBodyRead ----

    @Test
    void afterBodyRead_applicableInterceptor_applies() {
        stubPathMapping();
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);
        stubSupportRead(param, String.class, true);
        when(interceptor1.afterBodyRead("body", inputMessage, param, String.class, converter)).thenReturn("result");

        Object result = registry.afterBodyRead(request, "body", inputMessage, param, String.class, converter);

        assertNotNull(result);
        verify(interceptor1).supportBodyRead(param, String.class, converter);
        verify(interceptor1).afterBodyRead("body", inputMessage, param, String.class, converter);
    }

    @Test
    void afterBodyRead_interceptorNotSupport_skips() {
        stubPathMapping();
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);
        stubSupportRead(param, String.class, false);

        registry.afterBodyRead(request, "body", inputMessage, param, String.class, converter);

        verify(interceptor1, never()).afterBodyRead(any(), any(), any(), any(), any());
    }

    @Test
    void afterBodyRead_modifiesBody() {
        stubPathMapping();
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);
        stubSupportRead(param, String.class, true);
        when(interceptor1.afterBodyRead(eq("original"), any(), eq(param), eq(String.class), eq(converter)))
                .thenReturn("modified:original");

        Object result = registry.afterBodyRead(request, "original", inputMessage, param, String.class, converter);

        assertEquals("modified:original", result);
    }

    // ---- handleEmptyBodyRead ----

    @Test
    void handleEmptyBodyRead_delegatesToInterceptor() {
        stubPathMapping();
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);
        stubSupportRead(param, String.class, true);

        registry.handleEmptyBodyRead(request, null, inputMessage, param, String.class, converter);

        verify(interceptor1).supportBodyRead(param, String.class, converter);
        verify(interceptor1).handleEmptyBodyRead(null, inputMessage, param, String.class, converter);
    }

    @Test
    void handleEmptyBodyRead_interceptorNotSupport_skips() {
        stubPathMapping();
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);
        stubSupportRead(param, String.class, false);

        registry.handleEmptyBodyRead(request, null, inputMessage, param, String.class, converter);

        verify(interceptor1, never()).handleEmptyBodyRead(any(), any(), any(), any(), any());
    }

    // ---- beforeBodyWrite ----

    @Test
    void beforeBodyWrite_applicableInterceptor_applies() {
        stubPathMapping();
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);
        when(interceptor1.supportBodyWrite(param, converter)).thenReturn(true);

        registry.beforeBodyWrite("body", param, MediaType.APPLICATION_JSON, converter, request, response);

        verify(interceptor1).supportBodyWrite(param, converter);
        verify(interceptor1).beforeBodyWrite("body", param, MediaType.APPLICATION_JSON, converter, request, response);
    }

    @Test
    void beforeBodyWrite_interceptorNotSupport_skips() {
        stubPathMapping();
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);
        when(interceptor1.supportBodyWrite(param, converter)).thenReturn(false);

        registry.beforeBodyWrite("body", param, MediaType.APPLICATION_JSON, converter, request, response);

        verify(interceptor1, never()).beforeBodyWrite(any(), any(), any(), any(), any(), any());
    }
}
