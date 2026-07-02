package io.springperf.web.core.codec;

import io.springperf.web.core.codec.interceptor.HttpBodyCodecInterceptorRegistry;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.support.BodyHttpInputMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HttpBodyCodecRegistryTest {

    HttpBodyCodecRegistry registry;

    final Map<RequestAttribute<?>, Object> requestAttributeStore = new HashMap<>();

    @Mock
    HttpBodyConverter<String> converter1;

    @Mock
    HttpBodyConverter<String> converter2;

    @Mock
    BodyHttpInputMessage msg;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    RequestContext requestContext;

    @Mock
    MethodParameter parameter;

    HttpHeaders msgHeaders;

    @BeforeEach
    void setUp() {
        registry = new HttpBodyCodecRegistry();
        registry.interceptorRegistry = new HttpBodyCodecInterceptorRegistry();
        msgHeaders = new HttpHeaders();
        requestAttributeStore.clear();
    }

    private void stubPathMapping() {
        setupRequestContextWithStorage();
    }

    @SuppressWarnings("unchecked")
    private void setupRequestContextWithStorage() {
        lenient().when(request.getRequestContext()).thenReturn(requestContext);
        lenient().doAnswer(invocation -> {
            RequestAttribute<Object> key = invocation.getArgument(0);
            Object value = invocation.getArgument(1);
            requestAttributeStore.put(key, value);
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());
        lenient().doAnswer(invocation -> {
            RequestAttribute<?> key = invocation.getArgument(0);
            return requestAttributeStore.get(key);
        }).when(requestContext).getAttribute(any(RequestAttribute.class));
    }

    private void stubConverterCanRead(boolean match) {
        when(converter1.canRead(any(Type.class), any(), any())).thenReturn(match);
    }

    private void stubConverterRead() throws IOException {
        when(converter1.read(any(Type.class), any(), any(BodyHttpInputMessage.class))).thenReturn("test");
    }

    // ---- readBody ----

    @Test
    void readBody_noContentType_usesDefaultJson() throws Exception {
        stubPathMapping();
        when(msg.getHeaders()).thenReturn(msgHeaders);
        when(request.getCharacterEncoding()).thenReturn(null);
        stubConverterCanRead(true);
        stubConverterRead();
        when(msg.hasBody()).thenReturn(true);
        registry.converters.add(converter1);

        Object result = registry.readBody((Type) String.class, parameter, msg, request);

        assertEquals("test", result);
    }

    @Test
    void readBody_withContentType_usesIt() throws Exception {
        stubPathMapping();
        msgHeaders.setContentType(MediaType.APPLICATION_XML);
        when(msg.getHeaders()).thenReturn(msgHeaders);
        when(request.getCharacterEncoding()).thenReturn(null);
        when(converter1.canRead(any(Type.class), any(), eq(MediaType.APPLICATION_XML))).thenReturn(true);
        when(converter1.read(any(Type.class), any(), any(BodyHttpInputMessage.class))).thenReturn("<xml/>");
        when(msg.hasBody()).thenReturn(true);
        registry.converters.add(converter1);

        Object result = registry.readBody((Type) String.class, parameter, msg, request);

        assertEquals("<xml/>", result);
    }

    @Test
    void readBody_noConverterMatches_throwsException() {
        stubPathMapping();
        msgHeaders.setContentType(MediaType.APPLICATION_JSON);
        when(msg.getHeaders()).thenReturn(msgHeaders);
        when(request.getCharacterEncoding()).thenReturn(null);
        when(converter1.canRead(any(Type.class), any(), any())).thenReturn(false);
        when(converter2.canRead(any(Type.class), any(), any())).thenReturn(false);
        when(request.getMethod()).thenReturn(HttpMethod.POST);
        registry.converters.add(converter1);
        registry.converters.add(converter2);

        assertThrows(HttpMessageNotReadableException.class,
                () -> registry.readBody((Type) String.class, parameter, msg, request));
    }

    @Test
    void readBody_noConverterMatches_returnsNull() {
        stubPathMapping();
        msgHeaders.setContentType(MediaType.APPLICATION_JSON);
        when(msg.getHeaders()).thenReturn(msgHeaders);
        when(request.getCharacterEncoding()).thenReturn(null);
        when(converter1.canRead(any(Type.class), any(), any())).thenReturn(false);
        when(converter2.canRead(any(Type.class), any(), any())).thenReturn(false);
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        registry.converters.add(converter1);
        registry.converters.add(converter2);

        Object result = registry.readBody((Type) String.class, parameter, msg, request);

        assertNull(result);
    }

    @Test
    void readBody_noBody_invokesHandleEmptyBodyRead() {
        stubPathMapping();
        msgHeaders.setContentType(MediaType.APPLICATION_JSON);
        when(msg.getHeaders()).thenReturn(msgHeaders);
        when(request.getCharacterEncoding()).thenReturn(null);
        stubConverterCanRead(true);
        when(msg.hasBody()).thenReturn(false);
        registry.converters.add(converter1);

        Object result = registry.readBody((Type) String.class, parameter, msg, request);

        assertNull(result);
    }

    @Test
    void readBody_ioException_wrapsInHttpMessageNotReadableException() throws Exception {
        stubPathMapping();
        msgHeaders.setContentType(MediaType.APPLICATION_JSON);
        when(msg.getHeaders()).thenReturn(msgHeaders);
        when(request.getCharacterEncoding()).thenReturn(null);
        stubConverterCanRead(true);
        when(converter1.read(any(Type.class), any(), any(BodyHttpInputMessage.class)))
                .thenThrow(new IOException("io error"));
        when(msg.hasBody()).thenReturn(true);
        registry.converters.add(converter1);

        assertThrows(HttpMessageNotReadableException.class,
                () -> registry.readBody((Type) String.class, parameter, msg, request));
    }

    @Test
    void readBody_firstConverterMatches_skipsRest() throws Exception {
        stubPathMapping();
        msgHeaders.setContentType(MediaType.APPLICATION_JSON);
        when(msg.getHeaders()).thenReturn(msgHeaders);
        when(request.getCharacterEncoding()).thenReturn(null);
        when(converter1.canRead(any(Type.class), any(), any())).thenReturn(true);
        when(converter1.read(any(Type.class), any(), any(BodyHttpInputMessage.class)))
                .thenReturn("from converter1");
        when(msg.hasBody()).thenReturn(true);
        registry.converters.add(converter1);
        registry.converters.add(converter2);

        Object result = registry.readBody((Type) String.class, parameter, msg, request);

        assertEquals("from converter1", result);
        verify(converter2, never()).canRead(any(), any(), any());
    }

    @Test
    void readBody_withCharset_modifiesContentType() throws Exception {
        stubPathMapping();
        msgHeaders.setContentType(MediaType.APPLICATION_JSON);
        when(msg.getHeaders()).thenReturn(msgHeaders);
        when(request.getCharacterEncoding()).thenReturn(StandardCharsets.ISO_8859_1);
        stubConverterCanRead(true);
        stubConverterRead();
        when(msg.hasBody()).thenReturn(true);
        registry.converters.add(converter1);

        Object result = registry.readBody((Type) String.class, parameter, msg, request);

        assertEquals("test", result);
        assertEquals(StandardCharsets.ISO_8859_1, msgHeaders.getContentType().getCharset());
    }

    // ---- readBody cache behavior ----

    @Test
    void readBody_cachesConverter_andReusesOnSubsequentCalls() throws Exception {
        msgHeaders.setContentType(MediaType.APPLICATION_JSON);
        when(msg.getHeaders()).thenReturn(msgHeaders);
        when(request.getCharacterEncoding()).thenReturn(null);
        when(msg.hasBody()).thenReturn(true);
        registry.converters.add(converter1);
        registry.converters.add(converter2);
        when(converter1.canRead(any(Type.class), any(), any())).thenReturn(true);
        when(converter1.read(any(Type.class), any(), any(BodyHttpInputMessage.class))).thenReturn("test");

        PathMappingContext ctx = mock(PathMappingContext.class);
        MappingResult mr = MappingResult.matched(ctx);
        setupRequestContextWithStorage();
        MappingResult.set(request, mr);

        // First call: cache miss
        when(ctx.get(HttpBodyCodecRegistry.READ_BODY_CONVERTER_CACHE_KEY))
                .thenReturn(null);
        registry.readBody((Type) String.class, parameter, msg, request);
        verify(ctx).set(HttpBodyCodecRegistry.READ_BODY_CONVERTER_CACHE_KEY, converter1);

        // Second call: cache hit — converter1.canRead called again, converter2 never called
        when(ctx.get(HttpBodyCodecRegistry.READ_BODY_CONVERTER_CACHE_KEY))
                .thenReturn(converter1);
        clearInvocations(converter1, converter2);
        Object result = registry.readBody((Type) String.class, parameter, msg, request);

        assertEquals("test", result);
        // converter2.canRead should never be called (skipped the loop entirely)
        verify(converter2, never()).canRead(any(), any(), any());
    }

    @Test
    void readBody_cacheStale_fallsBackAndUpdates() throws Exception {
        msgHeaders.setContentType(MediaType.APPLICATION_JSON);
        when(msg.getHeaders()).thenReturn(msgHeaders);
        when(request.getCharacterEncoding()).thenReturn(null);
        when(msg.hasBody()).thenReturn(true);
        registry.converters.add(converter1);
        registry.converters.add(converter2);
        // converter1 no longer matches (stale), converter2 is the current match
        when(converter1.canRead(any(Type.class), any(), any())).thenReturn(false);
        when(converter2.canRead(any(Type.class), any(), any())).thenReturn(true);
        when(converter2.read(any(Type.class), any(), any(BodyHttpInputMessage.class))).thenReturn("from converter2");

        PathMappingContext ctx = mock(PathMappingContext.class);
        MappingResult mr = MappingResult.matched(ctx);
        setupRequestContextWithStorage();
        MappingResult.set(request, mr);

        // Cache holds stale converter1
        when(ctx.get(HttpBodyCodecRegistry.READ_BODY_CONVERTER_CACHE_KEY))
                .thenReturn(converter1);
        Object result = registry.readBody((Type) String.class, parameter, msg, request);

        assertEquals("from converter2", result);
        // Should update cache to converter2 (the new match)
        verify(ctx).set(HttpBodyCodecRegistry.READ_BODY_CONVERTER_CACHE_KEY, converter2);
    }

    @Test
    void readBody_noPathMappingContext_skipsCache() throws Exception {
        msgHeaders.setContentType(MediaType.APPLICATION_JSON);
        when(msg.getHeaders()).thenReturn(msgHeaders);
        when(request.getCharacterEncoding()).thenReturn(null);
        when(msg.hasBody()).thenReturn(true);
        registry.converters.add(converter1);
        when(converter1.canRead(any(Type.class), any(), any())).thenReturn(true);
        when(converter1.read(any(Type.class), any(), any(BodyHttpInputMessage.class))).thenReturn("test");

        // request context stubbed but no MappingResult set → PathMappingContext.get(request) returns null
        setupRequestContextWithStorage();

        Object result = registry.readBody((Type) String.class, parameter, msg, request);

        assertEquals("test", result);
    }

    @Test
    void writeBody_charSequence_convertsToString() throws Exception {
        stubPathMapping();
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(response.getCharacterEncoding()).thenReturn(null);
        when(converter1.canWrite(any(Type.class), any(), any())).thenReturn(true);
        when(converter1.getSupportedMediaTypes())
                .thenReturn(Collections.singletonList(MediaType.APPLICATION_JSON_UTF8));
        registry.converters.add(converter1);
        registry.allSupportedMediaTypes = Collections.singletonList(MediaType.APPLICATION_JSON_UTF8);
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        registry.writeBody("hello", parameter, request, response);

        verify(converter1).write(eq("hello"), eq((Type) String.class), any(), eq(response));
    }

    @Test
    void writeBody_normalBody_selectsMediaTypeAndWrites() throws Exception {
        stubPathMapping();
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(response.getCharacterEncoding()).thenReturn(null);
        when(parameter.getGenericParameterType()).thenReturn((Type) String.class);
        when(parameter.getParameterType()).thenReturn((Class) Object.class);
        when(converter1.canWrite(any(Type.class), any(), any())).thenReturn(true);
        when(converter1.getSupportedMediaTypes())
                .thenReturn(Collections.singletonList(MediaType.APPLICATION_JSON_UTF8));
        registry.converters.add(converter1);
        registry.allSupportedMediaTypes = Collections.singletonList(MediaType.APPLICATION_JSON_UTF8);
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        registry.writeBody(new Object(), parameter, request, response);

        verify(converter1).write(any(), eq((Type) String.class), any(), eq(response));
    }

    @Test
    void writeBody_bodyNotNullButNoMediaType_throwsException() {
        stubPathMapping();
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        registry.converters.add(converter1);
        registry.allSupportedMediaTypes = Collections.singletonList(MediaType.APPLICATION_JSON);
        when(converter1.canWrite(any(Type.class), any(), isNull())).thenReturn(true);
        when(converter1.getSupportedMediaTypes())
                .thenReturn(Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpHeaders reqHeaders = new HttpHeaders();
        reqHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_ATOM_XML));
        when(request.getHeaders()).thenReturn(reqHeaders);
        when(parameter.getParameterType()).thenReturn((Class) Object.class);
        when(parameter.getGenericParameterType()).thenReturn((Type) Object.class);

        assertThrows(HttpMessageNotWritableException.class,
                () -> registry.writeBody(new Object(), parameter, request, response));
    }

    @Test
    void writeBody_secondConverterWrites() throws Exception {
        stubPathMapping();
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(response.getCharacterEncoding()).thenReturn(null);
        when(converter1.canWrite(any(Type.class), any(), any())).thenReturn(false);
        when(converter2.canWrite(any(Type.class), any(), any())).thenReturn(true);
        when(converter2.getSupportedMediaTypes())
                .thenReturn(Collections.singletonList(MediaType.APPLICATION_JSON_UTF8));
        registry.converters.add(converter1);
        registry.converters.add(converter2);
        registry.allSupportedMediaTypes = Collections.singletonList(MediaType.APPLICATION_JSON_UTF8);
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        registry.writeBody("test", parameter, request, response);

        verify(converter2).write(eq("test"), any(Type.class), any(), eq(response));
    }

    // ---- chooseWriteMediaType ----

    @Test
    void chooseWriteMediaType_contentTypeAlreadySet_returnsIt() {
        stubPathMapping();
        HttpHeaders respHeaders = new HttpHeaders();
        respHeaders.setContentType(MediaType.APPLICATION_XML);
        lenient().when(response.getHeaders()).thenReturn(respHeaders);

        MediaType result = registry.chooseWriteMediaType("body", String.class, String.class, request, response);

        assertEquals(MediaType.APPLICATION_XML, result);
    }

    @Test
    void chooseWriteMediaType_noAcceptableTypes_fallbackToAll() {
        stubPathMapping();
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        registry.allSupportedMediaTypes = Collections.singletonList(MediaType.APPLICATION_JSON);
        registry.converters.add(converter1);
        when(converter1.canWrite(any(Type.class), eq(String.class), isNull())).thenReturn(true);
        when(converter1.getSupportedMediaTypes()).thenReturn(Collections.singletonList(MediaType.APPLICATION_JSON));

        MediaType result = registry.chooseWriteMediaType("body", String.class, String.class, request, response);

        assertNotNull(result);
        assertTrue(result.isConcrete());
    }

    @Test
    void chooseWriteMediaType_noMatch_returnsNull() {
        stubPathMapping();
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        HttpHeaders reqHeaders = new HttpHeaders();
        reqHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_ATOM_XML));
        when(request.getHeaders()).thenReturn(reqHeaders);
        registry.converters.add(converter1);
        when(converter1.canWrite(any(Type.class), eq(String.class), isNull())).thenReturn(true);
        when(converter1.getSupportedMediaTypes())
                .thenReturn(Collections.singletonList(MediaType.APPLICATION_JSON));
        registry.allSupportedMediaTypes = Collections.singletonList(MediaType.APPLICATION_JSON);

        MediaType result = registry.chooseWriteMediaType("body", String.class, String.class, request, response);

        assertNull(result);
    }

    @Test
    void chooseWriteMediaType_negotiation_returnsConcreteType() {
        stubPathMapping();
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        HttpHeaders reqHeaders = new HttpHeaders();
        reqHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        when(request.getHeaders()).thenReturn(reqHeaders);
        registry.allSupportedMediaTypes = Collections.singletonList(MediaType.APPLICATION_JSON);
        registry.converters.add(converter1);
        when(converter1.canWrite(any(Type.class), eq(String.class), isNull())).thenReturn(true);
        when(converter1.getSupportedMediaTypes()).thenReturn(Collections.singletonList(MediaType.APPLICATION_JSON));

        MediaType result = registry.chooseWriteMediaType("body", String.class, String.class, request, response);

        assertEquals(MediaType.APPLICATION_JSON, result);
    }

    // ---- getProducibleMediaTypes ----

    @Test
    void getProducibleMediaTypes_returnsFromConverters() {
        stubPathMapping();
        registry.allSupportedMediaTypes = Arrays.asList(MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML);
        registry.converters.add(converter1);
        when(converter1.canWrite(any(Type.class), eq(String.class), isNull())).thenReturn(true);
        when(converter1.getSupportedMediaTypes())
                .thenReturn(Arrays.asList(MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML));

        List<MediaType> result = registry.getProducibleMediaTypes(request, String.class, String.class);

        assertTrue(result.contains(MediaType.APPLICATION_JSON));
    }

    @Test
    void getProducibleMediaTypes_emptySupported_returnsAll() {
        stubPathMapping();

        List<MediaType> result = registry.getProducibleMediaTypes(request, String.class, String.class);

        assertEquals(Collections.singletonList(MediaType.ALL), result);
    }

    // ---- getGenericType ----

    @Test
    void getGenericType_nonHttpEntity_returnsGenericParameterType() {
        when(parameter.getGenericParameterType()).thenReturn((Type) String.class);
        when(parameter.getParameterType()).thenReturn((Class) String.class);

        Type result = registry.getGenericType(parameter);

        assertNotNull(result);
    }

    @Test
    void getGenericType_httpEntity_returnsWrappedGenericType() {
        MethodParameter httpEntityParam = mock(MethodParameter.class);
        when(httpEntityParam.getParameterType()).thenReturn((Class) HttpEntity.class);

        Type result = registry.getGenericType(httpEntityParam);

        assertNotNull(result);
    }

    // ---- getMostSpecificMediaType ----

    @Test
    void getMostSpecificMediaType_acceptMoreSpecific_returnsAccept() {
        MediaType acceptType = MediaType.APPLICATION_JSON;
        MediaType produceType = MediaType.parseMediaType("application/*");

        MediaType result = registry.getMostSpecificMediaType(acceptType, produceType);

        assertEquals(acceptType, result);
    }

    @Test
    void getMostSpecificMediaType_produceMoreSpecific_returnsProduce() {
        MediaType acceptType = MediaType.parseMediaType("application/*");
        MediaType produceType = MediaType.APPLICATION_JSON;

        MediaType result = registry.getMostSpecificMediaType(acceptType, produceType);

        assertNotNull(result);
    }
}
