package io.springperf.web.core.codec.interceptor;

import io.springperf.web.context.WebContext;
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

    // ---- getCodecInterceptor (with PathMappingContext → 缓存分支) ----

    @Test
    void getCodecInterceptor_withMappingContext_cachesByMappingContext() throws Exception {
        // 覆盖 MAPPING_CACHE_KEY 缓存分支（HttpBodyCodecInterceptorRegistry.java:60-64）：
        // 首次 realGet 后写入 mappingContext 缓存，第二次直接命中缓存不再重新解析
        stubPathMapping();
        io.springperf.web.core.mapping.PathMappingContext mappingContext =
                mock(io.springperf.web.core.mapping.PathMappingContext.class);
        // 模拟真实缓存：set 保存数组，get 返回已缓存值
        final HttpBodyCodecInterceptor[][] cached = new HttpBodyCodecInterceptor[1][];
        when(mappingContext.get(HttpBodyCodecInterceptorRegistry.MAPPING_CACHE_KEY))
                .thenAnswer(inv -> cached[0]);
        doAnswer(inv -> {
            cached[0] = inv.getArgument(1);
            return null;
        }).when(mappingContext).set(eq(HttpBodyCodecInterceptorRegistry.MAPPING_CACHE_KEY), any());
        MethodParameter param = mock(MethodParameter.class);
        when(param.getContainingClass()).thenReturn((Class) String.class);
        addInterceptor(interceptor1);

        // PathMappingContext.get(request) 依赖 MappingResult（存于 RequestContext attribute）
        java.util.Map<io.springperf.web.http.RequestAttribute<?>, Object> fastAttrs = new java.util.HashMap<>();
        when(requestContext.getAttribute(any(io.springperf.web.http.RequestAttribute.class)))
                .thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            fastAttrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(io.springperf.web.http.RequestAttribute.class), any());
        io.springperf.web.core.mapping.MappingResult.set(request,
                io.springperf.web.core.mapping.MappingResult.matched(mappingContext));

        HttpBodyCodecInterceptor[] first = registry.getCodecInterceptor(request, param);
        HttpBodyCodecInterceptor[] second = registry.getCodecInterceptor(request, param);

        assertEquals(1, first.length);
        assertSame(first, second, "第二次调用应命中 mappingContext 缓存返回同一数组");
        verify(mappingContext).set(HttpBodyCodecInterceptorRegistry.MAPPING_CACHE_KEY, first);
        // realGet 只执行了一次（第二次命中缓存直接返回）
        verify(param, times(1)).getContainingClass();
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

    /* ==================== 生命周期：initWithWebContext / initCodecInterceptors ==================== */

    public static class CodecAdvice implements HttpBodyCodecInterceptor {
        @Override
        public boolean supportBodyRead(MethodParameter methodParameter, Type targetType, HttpBodyConverter converter) {
            return true;
        }

        @Override
        public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, HttpBodyConverter converter) {
            return inputMessage;
        }

        @Override
        public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, HttpBodyConverter converter) {
            return body;
        }

        @Override
        public Object handleEmptyBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, HttpBodyConverter converter) {
            return body;
        }

        @Override
        public boolean supportBodyWrite(MethodParameter methodParameter, HttpBodyConverter converter) {
            return true;
        }

        @Override
        public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType, HttpBodyConverter converter,
                                      org.springframework.http.server.ServerHttpRequest request,
                                      org.springframework.http.server.ServerHttpResponse response) {
            return body;
        }
    }

    public static class AnnotatedConfig {
        @org.springframework.web.bind.annotation.ControllerAdvice
        public static class AnnotatedAdvice extends CodecAdvice {
        }

        @org.springframework.context.annotation.Bean
        public AnnotatedAdvice annotatedAdvice() {
            return new AnnotatedAdvice();
        }
    }

    @Test
    void initWithWebContext_scansControllerAdviceAndInterceptors() throws Exception {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext ctx =
                     new org.springframework.context.annotation.AnnotationConfigApplicationContext(AnnotatedConfig.class)) {
            WebContext webContext = mock(WebContext.class);
            when(webContext.getCtx()).thenReturn(ctx);
            HttpBodyCodecInterceptorRegistry reg = new HttpBodyCodecInterceptorRegistry();
            reg.initWithWebContext(webContext);
            reg.initComponentPhase2();

            assertNotNull(reg.getWebContext());
        }
    }

    @Test
    void initWithWebContext_emptyContext_initializesWithoutBeans() {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext ctx =
                     new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.refresh();
            WebContext webContext = mock(WebContext.class);
            when(webContext.getCtx()).thenReturn(ctx);
            HttpBodyCodecInterceptorRegistry reg = new HttpBodyCodecInterceptorRegistry();
            reg.initWithWebContext(webContext);
            assertNotNull(reg.getWebContext());
        }
    }
}
