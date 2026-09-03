package io.springperf.web.core.codec;

import io.springperf.web.annotation.Optimize;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.codec.interceptor.HttpBodyCodecInterceptorRegistry;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebHttpHeaders;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.support.BodyHttpInputMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpBodyCodecRegistryCoverageTest {

    @SuppressWarnings("unused")
    @Optimize
    static class OptimizeController {
        public String echo() {
            return "echo";
        }
    }

    private HttpBodyCodecRegistry registry;
    private HttpBodyCodecInterceptorRegistry interceptorRegistry;
    private final Map<RequestAttribute<?>, Object> attributeStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        registry = new HttpBodyCodecRegistry();
        interceptorRegistry = mock(HttpBodyCodecInterceptorRegistry.class);
        when(interceptorRegistry.beforeBodyWrite(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        registry.interceptorRegistry = interceptorRegistry;
        attributeStore.clear();
    }

    @Test
    void initWithWebContext_registersJacksonAndPhase2BuildsConverters() throws Exception {
        WebContext webContext = mock(WebContext.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(webContext.getCtx()).thenReturn(applicationContext);
        when(applicationContext.getBeansOfType(any(Class.class))).thenReturn(Collections.emptyMap());

        HttpBodyCodecRegistry local = new HttpBodyCodecRegistry();
        local.initWithWebContext(webContext);
        local.initComponentPhase1();
        local.initComponentPhase2();

        assertFalse(local.converters.isEmpty());
        assertTrue(local.allSupportedMediaTypes.contains(MediaType.APPLICATION_JSON));
    }

    @Test
    void toHttpBodyConverter_wrapsGenericAndAdaptsPlain() {
        GenericHttpMessageConverter<Object> genericConverter = mock(GenericHttpMessageConverter.class);
        HttpBodyConverter wrapped = registry.toHttpBodyConverter(genericConverter);
        assertTrue(wrapped instanceof WrappedHttpBodyConverter);

        HttpMessageConverter<Object> plainConverter = mock(HttpMessageConverter.class);
        HttpBodyConverter adapted = registry.toHttpBodyConverter(plainConverter);
        assertTrue(adapted instanceof AdaptedHttpBodyConverter);
    }

    @Test
    void getConverters_returnsLiveList() {
        assertSame(registry.converters, registry.getConverters());
    }

    @Test
    void registerConverter_recomputesSortedSupportedMediaTypes() {
        HttpBodyConverter xml = mock(HttpBodyConverter.class);
        when(xml.getSupportedMediaTypes())
                .thenReturn(Collections.singletonList(MediaType.parseMediaType("application/xml;charset=UTF-8")));
        when(xml.getComponentName()).thenReturn("xmlCoverageConverter");
        registry.registerConverter(xml);

        HttpBodyConverter text = mock(HttpBodyConverter.class);
        when(text.getSupportedMediaTypes())
                .thenReturn(Collections.singletonList(MediaType.parseMediaType("text/plain;charset=UTF-8")));
        when(text.getComponentName()).thenReturn("textCoverageConverter");
        registry.registerConverter(text);

        HttpBodyConverter json = mock(HttpBodyConverter.class);
        when(json.getSupportedMediaTypes())
                .thenReturn(Collections.singletonList(MediaType.parseMediaType("application/json;charset=UTF-8")));
        when(json.getComponentName()).thenReturn("jsonCoverageConverter");
        registry.registerConverter(json);

        List<MediaType> sorted = registry.allSupportedMediaTypes;
        assertEquals(3, sorted.size());
        assertEquals("json", sorted.get(0).getSubtype());
        assertEquals("xml", sorted.get(1).getSubtype());
        assertEquals("text", sorted.get(2).getType());
    }

    @Test
    void readBody_cachedConverterWithoutBody_usesEmptyBodyHandler() throws Exception {
        WebServerHttpRequest request = requestWithContextStorage();
        PathMappingContext ctx = mock(PathMappingContext.class);
        HttpBodyConverter cachedConverter = mock(HttpBodyConverter.class);
        when(cachedConverter.canRead(any(Type.class), any(), any(), any(), any())).thenReturn(true);
        when(ctx.get(HttpBodyCodecRegistry.READ_BODY_CONVERTER_CACHE_KEY)).thenReturn(cachedConverter);
        MappingResult.set(request, MappingResult.matched(ctx));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        BodyHttpInputMessage msg = mock(BodyHttpInputMessage.class);
        when(msg.getHeaders()).thenReturn(headers);
        when(request.getCharacterEncoding()).thenReturn(null);
        when(msg.hasBody()).thenReturn(false);
        MethodParameter parameter = mock(MethodParameter.class);

        Object result = registry.readBody((Type) String.class, parameter, msg, request);

        assertNull(result);
        verify(interceptorRegistry).handleEmptyBodyRead(
                eq(request), isNull(), eq(msg), eq(parameter), eq((Type) String.class), eq(cachedConverter));
    }

    @Test
    void readBody_contentTypeCharsetDiffersFromRequestEncoding_resetsCharset() throws Exception {
        WebServerHttpRequest request = requestWithContextStorage();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/xml;charset=ISO-8859-1"));
        BodyHttpInputMessage msg = mock(BodyHttpInputMessage.class);
        when(msg.getHeaders()).thenReturn(headers);
        when(request.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        HttpBodyConverter converter = converterCanRead();
        when(converter.read(any(Type.class), any(), any(HttpInputMessage.class), any(), any())).thenReturn("<x/>");
        when(interceptorRegistry.beforeBodyRead(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(interceptorRegistry.afterBodyRead(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(msg.hasBody()).thenReturn(true);
        registry.converters.add(converter);

        Object result = registry.readBody((Type) String.class, mock(MethodParameter.class), msg, request);

        assertEquals("<x/>", result);
        assertEquals(StandardCharsets.UTF_8, headers.getContentType().getCharset());
        assertEquals("xml", headers.getContentType().getSubtype());
    }

    @Test
    void writeBody_nullValue_returnsImmediately() throws Exception {
        registry.writeBody(null, null, null, null);
    }

    @Test
    void writeBody_emptyOptional_skipsWrite() throws Exception {
        WebServerHttpRequest request = requestWithContextStorage();
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        registry.writeBody(Optional.empty(), mock(MethodParameter.class), request, response);

        verify(interceptorRegistry, never()).beforeBodyWrite(any(), any(), any(), any(), any(), any());
    }

    @Test
    void writeBody_presentOptional_unwrapsAndWrites() throws Exception {
        WebServerHttpRequest request = requestWithContextStorage();
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        when(response.getHeaders()).thenReturn(responseHeaders);
        when(response.getCharacterEncoding()).thenReturn(null);
        HttpBodyConverter converter = converterCanWrite();
        registry.converters.add(converter);

        registry.writeBody(Optional.of("hello"), mock(MethodParameter.class), request, response);

        verify(converter).write(eq("hello"), eq((Type) String.class), eq(MediaType.APPLICATION_JSON),
                eq(response), eq(request), eq(response), isNull());
    }

    @Test
    void writeBody_producesWildcardMediaType_mapsToJson() throws Exception {
        WebServerHttpRequest request = requestWithContextStorage();
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getProducibleMediaTypes()).thenReturn(Collections.singletonList(MediaType.parseMediaType("application/*")));
        MappingResult.set(request, MappingResult.matched(ctx));
        WebHttpHeaders requestHeaders = new WebHttpHeaders();
        requestHeaders.set("Accept", "application/*");
        when(request.getHeaders()).thenReturn(requestHeaders);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(response.getCharacterEncoding()).thenReturn(null);
        HttpBodyConverter converter = converterCanWrite();
        registry.converters.add(converter);

        registry.writeBody("hello", mock(MethodParameter.class), request, response);

        verify(converter).write(eq("hello"), eq((Type) String.class), eq(MediaType.APPLICATION_JSON),
                eq(response), eq(request), eq(response), eq(ctx));
    }

    @Test
    void writeBody_negotiationCacheFull_clearsBeforeWritingNewEntry() throws Exception {
        clearMethodCache();
        Method echo = OptimizeController.class.getMethod("echo");
        PathMappingContext ctx = new PathMappingContext(
                new HandlerMethod(new OptimizeController(), echo), Collections.emptyList(), "/optimize-coverage");
        Map<String, Object> preloaded = new ConcurrentHashMap<>();
        for (int i = 0; i < 64; i++) {
            preloaded.put("pre-" + i, new Object());
        }
        ctx.set(HttpBodyCodecRegistry.WRITE_NEGOTIATION_CACHE_KEY, (Map) preloaded);

        WebServerHttpRequest request = requestWithContextStorage();
        MappingResult.set(request, MappingResult.matched(ctx));
        WebHttpHeaders requestHeaders = new WebHttpHeaders();
        requestHeaders.set("Accept", "*/*");
        when(request.getHeaders()).thenReturn(requestHeaders);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(response.getCharacterEncoding()).thenReturn(null);
        HttpBodyConverter converter = converterCanWrite();
        registry.converters.add(converter);

        registry.writeBody("hello", new MethodParameter(echo, -1), request, response);

        Map<?, ?> after = ctx.get(HttpBodyCodecRegistry.WRITE_NEGOTIATION_CACHE_KEY);
        assertNotNull(after);
        assertEquals(1, after.size());
        assertTrue(after.containsKey("*/*"));
    }

    @Test
    void writeBody_wildcardTypeWithParameters_excludedFromNegotiation() {
        WebServerHttpRequest request = requestWithContextStorage();
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        HttpBodyConverter converter = mock(HttpBodyConverter.class);
        when(converter.canWrite(any(), any(), isNull(), any(), any(), any())).thenReturn(true);
        when(converter.getSupportedMediaTypes())
                .thenReturn(Collections.singletonList(MediaType.parseMediaType("application/*;q=0.9")));
        registry.converters.add(converter);

        assertThrows(HttpMessageNotWritableException.class,
                () -> registry.writeBody("hello", mock(MethodParameter.class), request, response));
    }

    private HttpBodyConverter converterCanRead() {
        HttpBodyConverter converter = mock(HttpBodyConverter.class);
        when(converter.canRead(any(Type.class), any(), any(), any(), any())).thenReturn(true);
        return converter;
    }

    private HttpBodyConverter converterCanWrite() {
        HttpBodyConverter converter = mock(HttpBodyConverter.class);
        when(converter.canWrite(any(Type.class), any(), any(), any(), any(), any())).thenReturn(true);
        when(converter.getSupportedMediaTypes())
                .thenReturn(Collections.singletonList(MediaType.APPLICATION_JSON));
        return converter;
    }

    private WebServerHttpRequest requestWithContextStorage() {
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        RequestContext requestContext = mock(RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        doAnswer(invocation -> {
            attributeStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());
        when(requestContext.getAttribute(any(RequestAttribute.class)))
                .thenAnswer(invocation -> attributeStore.get(invocation.getArgument(0)));
        return request;
    }

    @SuppressWarnings("unchecked")
    private static void clearMethodCache() throws Exception {
        Field field = Class.forName("io.springperf.web.core.mapping.MappingHandlerMethod")
                .getDeclaredField("methodCacheInstanceMap");
        field.setAccessible(true);
        Map<Object, Object> map = (Map<Object, Object>) field.get(null);
        map.clear();
    }
}