package io.springperf.web.core.async.reactive;

import io.springperf.web.core.async.stream.SseEmitter;
import io.springperf.web.core.async.stream.SseJsonEmitter;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.json.JsonConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link ReactiveReturnValueResolver} 的纯逻辑方法：
 * 构造器选择/参数解析、supports 判定、MediaType 包含关系、可生产类型解析。
 */
class ReactiveReturnValueResolverDetailsTest {

    private ReactiveReturnValueResolver resolver;
    private WebServerHttpRequest request;
    private RequestContext requestContext;
    private WebServerHttpResponse response;

    @BeforeEach
    void setUp() throws Exception {
        resolver = new ReactiveReturnValueResolver();
        JsonConverter jsonConverter = mock(JsonConverter.class);
        io.springperf.web.core.async.AsyncSupportRegistry registry =
                org.mockito.Mockito.spy(new io.springperf.web.core.async.AsyncSupportRegistry());
        doReturn(jsonConverter).when(registry).getJsonConverter();
        setField("asyncSupportRegistry", registry);
        setField("adapterRegistry", org.springframework.core.ReactiveAdapterRegistry.getSharedInstance());
        request = mock(WebServerHttpRequest.class);
        response = mock(WebServerHttpResponse.class);
        requestContext = mock(RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        Map<RequestAttribute<?>, Object> attrs = new HashMap<>();
        when(requestContext.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());
    }

    private void setField(String name, Object value) throws Exception {
        java.lang.reflect.Field f = ReactiveReturnValueResolver.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(resolver, value);
    }

    private MethodParameter param(Class<?> type, Class<?>... generics) throws Exception {
        Method method = TypeHolder.class.getMethod("handler", type);
        return new MethodParameter(method, 0);
    }

    @SuppressWarnings("unused")
    static class TypeHolder {
        public void handler(org.reactivestreams.Publisher<String> p) {}
        public void handler(String s) {}
        public void handler(ResponseEntity<org.reactivestreams.Publisher<String>> p) {}
    }

    static class MonoPublisher {
        public <T> T get() { return null; }
    }

    // ==================== selectBestConstructor ====================

    @Test
    void selectBestConstructor_singleLongCtor_returnsIt() {
        Constructor<?> ctor = resolver.selectBestConstructor(SseEmitter.class);
        assertNotNull(ctor);
        assertEquals(1, ctor.getParameterCount(), "SseEmitter 过滤后应选 (Long) 构造器");
    }

    @Test
    void selectBestConstructor_maxTwoArgs_returnsTwo() {
        // SseJsonEmitter(Long, JsonConverter) 2 参 > (JsonConverter) 1 参
        Constructor<?> ctor = resolver.selectBestConstructor(SseJsonEmitter.class);
        assertNotNull(ctor);
        assertEquals(2, ctor.getParameterCount());
    }

    @Test
    void selectBestConstructor_nullType_returnsNull() {
        assertNull(resolver.selectBestConstructor(null));
    }

    @Test
    void selectBestConstructor_conflictingSameCount_throws() {
        // ConflictEmitter 自身声明 (Long) 与 (JsonConverter) 两个 1 参构造器 →
        // 过滤后同参数量（1=1）冲突应抛异常
        assertThrows(IllegalStateException.class,
                () -> resolver.selectBestConstructor(ConflictEmitter.class));
    }

    /** 自身同时声明 (Long) 与 (JsonConverter) 两个 1 参构造器的 StreamEmitter */
    abstract static class ConflictEmitter extends SseEmitter {
        @SuppressWarnings("unused")
        public ConflictEmitter(Long timeout) {
            super(timeout);
        }

        @SuppressWarnings("unused")
        public ConflictEmitter(JsonConverter unused) {
            super();
        }
    }

    @Test
    void isConstructorSupported_allSupported_returnsTrue() throws Exception {
        assertTrue(resolver.isConstructorSupported(SseEmitter.class.getConstructor(Long.class)));
        assertTrue(resolver.isConstructorSupported(SseJsonEmitter.class.getConstructor(JsonConverter.class)));
    }

    @Test
    void isConstructorSupported_unsupportedParam_returnsFalse() throws Exception {
        assertFalse(resolver.isConstructorSupported(
                SseEmitter.class.getConstructor(boolean.class)));
    }

    // ==================== getConstructorArg ====================

    @Test
    void getConstructorArg_long_returnsTimeout() {
        ReactiveConfig config = new ReactiveConfig(null, null, 100, 20, 5000L);
        assertEquals(5000L, resolver.getConstructorArg(long.class, config, request));
        assertEquals(5000L, resolver.getConstructorArg(Long.class, config, request));
    }

    @Test
    void getConstructorArg_jsonConverter_returnsConverter() {
        ReactiveConfig config = new ReactiveConfig(null, null, 100, 20, 5000L);
        assertNotNull(resolver.getConstructorArg(JsonConverter.class, config, request));
    }

    @Test
    void getConstructorArg_unsupported_throws() {
        ReactiveConfig config = new ReactiveConfig(null, null, 100, 20, 5000L);
        assertThrows(IllegalStateException.class,
                () -> resolver.getConstructorArg(String.class, config, request));
    }

    // ==================== supports ====================

    @Test
    void supportsReturnType_reactive_returnsTrue() throws Exception {
        MethodParameter p = new MethodParameter(TypeHolder.class.getMethod("handler",
                org.reactivestreams.Publisher.class), 0);
        assertTrue(resolver.supportsReturnType(p, null));
    }

    @Test
    void supportsReturnType_nonReactive_returnsFalse() throws Exception {
        MethodParameter p = new MethodParameter(TypeHolder.class.getMethod("handler", String.class), 0);
        assertFalse(resolver.supportsReturnType(p, null));
    }

    @Test
    void supportsReturnValue_null_false() {
        assertFalse(resolver.supportsReturnValue(null, request, response));
    }

    @Test
    void supportsReturnValue_nonReactive_false() {
        assertFalse(resolver.supportsReturnValue("plain", request, response));
    }

    // ==================== containMediaType / getSupportMediaTypeList ====================

    @Test
    void containMediaType_responseContentTypeMatched_returnsTrue() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        when(response.getHeaders()).thenReturn(headers);
        assertTrue(resolver.containMediaType(MediaType.TEXT_EVENT_STREAM, request, response));
    }

    @Test
    void containMediaType_acceptMatched_setsConcreteContentType() {
        HttpHeaders responseHeaders = new HttpHeaders();
        when(response.getHeaders()).thenReturn(responseHeaders);
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getProducibleMediaTypes())
                .thenReturn(Collections.singletonList(new MediaType("application", "stream+json")));
        MappingResult.set(request, MappingResult.matched(ctx));

        assertTrue(resolver.containMediaType(
                new MediaType("application", "stream+json"), request, response));
        assertEquals(new MediaType("application", "stream+json"),
                responseHeaders.getContentType());
    }

    @Test
    void getSupportMediaTypeList_fromProducibleMediaTypes() {
        PathMappingContext ctx = mock(PathMappingContext.class);
        List<MediaType> producible = Collections.singletonList(MediaType.APPLICATION_JSON);
        when(ctx.getProducibleMediaTypes()).thenReturn(producible);
        MappingResult.set(request, MappingResult.matched(ctx));
        assertEquals(producible, resolver.getSupportMediaTypeList(request));
    }

    @Test
    void getSupportMediaTypeList_fallbackToAccept() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        when(request.getHeaders()).thenReturn(headers);
        assertEquals(Collections.singletonList(MediaType.APPLICATION_JSON),
                resolver.getSupportMediaTypeList(request));
    }
}