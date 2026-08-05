package io.springperf.web.core.codec;

import io.springperf.web.annotation.Optimize;
import io.springperf.web.core.codec.interceptor.HttpBodyCodecInterceptorRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.ConsumeOrProduceMatcher;
import io.springperf.web.core.mapping.match.MediaTypeExpressionSupport;
import io.springperf.web.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2b 方法级内容协商缓存的行为测试（仅 @Optimize 方法启用）。
 * <p>
 * 通过 canWrite 的 mediaType 参数区分命中/全量两条路径：
 * 全量协商 loop 传 null mediaType，缓存命中后的二次 canWrite 传 concrete mediaType。
 * <p>
 * ctx 通过真实 PathMappingContext + MappingResult.set 注入，isOptimize() 由
 * 真实 PathMappingContext 内层 InvokableHandlerMethod 依据 @Optimize 解析。
 */
class HttpBodyCodecRegistryWriteNegotiationTest {

    @SuppressWarnings("unused")
    @Optimize
    static class OptimizeController {
        public String echo() { return "echo"; }
    }

    @SuppressWarnings("unused")
    static class PlainController {
        public String echo() { return "echo"; }
    }

    private HttpBodyCodecRegistry registry;
    private WebServerHttpRequest request;
    private WebHttpHeaders requestHeaders;
    private RequestContext requestContext;
    private Method optimizeEcho;
    private Method plainEcho;
    private MethodParameter optimizeReturnType;
    private MethodParameter plainReturnType;
    private final Map<RequestAttribute<?>, Object> requestAttributeStore = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        // 协商缓存存进 MappingHandlerMethod 静态 methodCacheInstanceMap（JVM 全局），
        // 每个测试用同一 echo 方法会串味，@BeforeEach 先清空静态表。
        clearMethodCache();

        optimizeEcho = OptimizeController.class.getMethod("echo");
        plainEcho = PlainController.class.getMethod("echo");
        optimizeReturnType = new MethodParameter(optimizeEcho, -1);
        plainReturnType = new MethodParameter(plainEcho, -1);

        request = mock(WebServerHttpRequest.class);
        requestHeaders = new WebHttpHeaders();
        requestContext = mock(RequestContext.class);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(request.getHeaders()).thenReturn(requestHeaders);
        stubRequestContextWithStorage();
        requestAttributeStore.clear();

        registry = new HttpBodyCodecRegistry();
        HttpBodyCodecInterceptorRegistry interceptorRegistry = mock(HttpBodyCodecInterceptorRegistry.class);
        when(interceptorRegistry.beforeBodyWrite(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        registry.interceptorRegistry = interceptorRegistry;
    }

    @SuppressWarnings("unchecked")
    private void stubRequestContextWithStorage() {
        doAnswer(invocation -> {
            RequestAttribute<Object> key = invocation.getArgument(0);
            Object value = invocation.getArgument(1);
            requestAttributeStore.put(key, value);
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());
        doAnswer(invocation -> {
            RequestAttribute<?> key = invocation.getArgument(0);
            return requestAttributeStore.get(key);
        }).when(requestContext).getAttribute(any(RequestAttribute.class));
    }

    @SuppressWarnings("unchecked")
    private static void clearMethodCache() throws Exception {
        Field field = MappingHandlerMethod.class.getDeclaredField("methodCacheInstanceMap");
        field.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) field.get(null);
        map.clear();
    }

    private PathMappingContext optimizedContext() {
        return new PathMappingContext(new HandlerMethod(new OptimizeController(), optimizeEcho),
                Collections.emptyList(), "/optimize");
    }

    private PathMappingContext plainContext() {
        return new PathMappingContext(new HandlerMethod(new PlainController(), plainEcho),
                Collections.emptyList(), "/plain");
    }

    private static ConsumeOrProduceMatcher jsonProduceMatcher() {
        return new ConsumeOrProduceMatcher(true,
                List.of(new MediaTypeExpressionSupport(MediaType.APPLICATION_JSON, false)));
    }

    private PathMappingContext optimizedProduceContext() {
        return new PathMappingContext(new HandlerMethod(new OptimizeController(), optimizeEcho),
                List.of(jsonProduceMatcher()), "/optimize-produce");
    }

    private PathMappingContext plainProduceContext() {
        return new PathMappingContext(new HandlerMethod(new PlainController(), plainEcho),
                List.of(jsonProduceMatcher()), "/plain-produce");
    }

    private void setMatchedContext(PathMappingContext ctx) {
        MappingResult.set(request, MappingResult.matched(ctx));
    }

    private HttpBodyConverter jsonConverter() {
        HttpBodyConverter converter = mock(HttpBodyConverter.class);
        when(converter.getSupportedMediaTypes()).thenReturn(List.of(MediaType.APPLICATION_JSON));
        when(converter.canWrite(any(), any(), any(), any(), any(), any())).thenReturn(true);
        return converter;
    }

    private void write(String body, MethodParameter returnType) throws Exception {
        // 每个请求独立的 response：writeBody 尾段会 setContentType，
        // 共享实例会导致后续调用误入 path1（Content-Type 已设置），绕过 2b 缓存逻辑。
        registry.writeBody(body, returnType, request, newResponse());
    }

    private WebServerHttpResponse newResponse() {
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getHeaders()).thenReturn(new HttpHeaders());
        when(resp.getCharacterEncoding()).thenReturn(null);
        return resp;
    }

    // ==================== @Optimize 方法：缓存命中 ====================

    @Test
    void writeBody_optimizeMethod_sameAccept_hitsCache() throws Exception {
        HttpBodyConverter converter = jsonConverter();
        registry.converters.add(converter);
        setMatchedContext(optimizedContext());

        write("first", optimizeReturnType);
        write("second", optimizeReturnType);

        ArgumentCaptor<MediaType> mediaTypeCaptor = ArgumentCaptor.forClass(MediaType.class);
        verify(converter, times(2)).canWrite(any(), any(), mediaTypeCaptor.capture(), any(), any(), any());
        List<MediaType> captured = mediaTypeCaptor.getAllValues();
        assertEquals(2, captured.size());
        assertNull(captured.get(0));            // 第一次：全量协商，mediaType=null
        assertEquals(MediaType.APPLICATION_JSON, captured.get(1)); // 第二次：缓存命中二次 canWrite
        verify(converter, times(2)).write(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void writeBody_optimizeMethod_cacheCanWriteFail_fallsBackAndDoesNotCache() throws Exception {
        HttpBodyConverter converter = mock(HttpBodyConverter.class);
        when(converter.getSupportedMediaTypes()).thenReturn(List.of(MediaType.APPLICATION_JSON));
        when(converter.canWrite(any(), any(), isNull(), any(), any(), any())).thenReturn(true);   // 全量 loop
        when(converter.canWrite(any(), any(), notNull(), any(), any(), any())).thenReturn(false); // 命中二次校验失败
        registry.converters.add(converter);
        setMatchedContext(optimizedContext());

        write("a", optimizeReturnType);
        write("b", optimizeReturnType);
        write("c", optimizeReturnType);

        // 第一次 miss：1 次全量；之后每次：1 次二次校验(false) + 1 次全量回退 = 2。共 1 + 2 + 2 = 5。
        ArgumentCaptor<MediaType> mediaTypeCaptor = ArgumentCaptor.forClass(MediaType.class);
        verify(converter, times(5)).canWrite(any(), any(), mediaTypeCaptor.capture(), any(), any(), any());
        List<MediaType> captured = mediaTypeCaptor.getAllValues();
        assertNull(captured.get(0));
        assertEquals(MediaType.APPLICATION_JSON, captured.get(1));
        assertNull(captured.get(2));
        assertEquals(MediaType.APPLICATION_JSON, captured.get(3));
        assertNull(captured.get(4));
    }

    // ==================== @Optimize 方法：缓存 key 维度 ====================

    @Test
    void writeBody_optimizeMethod_differentAccept_usesSeparateEntries() throws Exception {
        HttpBodyConverter converter = jsonConverter();
        registry.converters.add(converter);
        setMatchedContext(optimizedContext());

        write("a", optimizeReturnType);          // Accept 缺失 → "*/*"
        requestHeaders.set("Accept", "application/json");
        write("b", optimizeReturnType);

        ArgumentCaptor<MediaType> mediaTypeCaptor = ArgumentCaptor.forClass(MediaType.class);
        verify(converter, times(2)).canWrite(any(), any(), mediaTypeCaptor.capture(), any(), any(), any());
        assertNull(mediaTypeCaptor.getAllValues().get(0)); // 第一次 miss
        assertNull(mediaTypeCaptor.getAllValues().get(1)); // 不同 key → 第二次仍 miss
    }

    @Test
    void writeBody_optimizeMethod_acceptCaseInsensitive_hitsCache() throws Exception {
        HttpBodyConverter converter = jsonConverter();
        registry.converters.add(converter);
        setMatchedContext(optimizedContext());

        requestHeaders.set("Accept", "Application/JSON");
        write("a", optimizeReturnType);
        requestHeaders.set("Accept", "application/json");
        write("b", optimizeReturnType);

        ArgumentCaptor<MediaType> mediaTypeCaptor = ArgumentCaptor.forClass(MediaType.class);
        verify(converter, times(2)).canWrite(any(), any(), mediaTypeCaptor.capture(), any(), any(), any());
        assertNull(mediaTypeCaptor.getAllValues().get(0));
        assertEquals(MediaType.APPLICATION_JSON, mediaTypeCaptor.getAllValues().get(1));
    }

    @Test
    void writeBody_optimizeMethod_blankAccept_normalizedToWildcard() throws Exception {
        HttpBodyConverter converter = jsonConverter();
        registry.converters.add(converter);
        setMatchedContext(optimizedContext());

        requestHeaders.set("Accept", "   ");
        write("a", optimizeReturnType);
        requestHeaders.set("Accept", "*/*");
        write("b", optimizeReturnType);

        ArgumentCaptor<MediaType> mediaTypeCaptor = ArgumentCaptor.forClass(MediaType.class);
        verify(converter, times(2)).canWrite(any(), any(), mediaTypeCaptor.capture(), any(), any(), any());
        assertNull(mediaTypeCaptor.getAllValues().get(0));
        assertEquals(MediaType.APPLICATION_JSON, mediaTypeCaptor.getAllValues().get(1));
    }

    // ==================== 缓存失效 ====================

    @Test
    void writeBody_registerConverter_clearsNegotiationCache() throws Exception {
        HttpBodyConverter converter = jsonConverter();
        registry.converters.add(converter);
        setMatchedContext(optimizedContext());

        write("a", optimizeReturnType);                     // 填充缓存
        registry.registerConverter(converter);              // converter 集合变更 → 缓存清空
        write("b", optimizeReturnType);

        ArgumentCaptor<MediaType> mediaTypeCaptor = ArgumentCaptor.forClass(MediaType.class);
        verify(converter, times(2)).canWrite(any(), any(), mediaTypeCaptor.capture(), any(), any(), any());
        assertNull(mediaTypeCaptor.getAllValues().get(0));
        assertNull(mediaTypeCaptor.getAllValues().get(1)); // 缓存已清 → 第二次 miss
    }

    // ==================== 降级路径（不缓存） ====================

    @Test
    void writeBody_plainMethod_degradesToNoCache() throws Exception {
        HttpBodyConverter converter = jsonConverter();
        registry.converters.add(converter);
        setMatchedContext(plainContext());

        write("a", plainReturnType);
        write("b", plainReturnType);

        ArgumentCaptor<MediaType> mediaTypeCaptor = ArgumentCaptor.forClass(MediaType.class);
        verify(converter, times(2)).canWrite(any(), any(), mediaTypeCaptor.capture(), any(), any(), any());
        assertNull(mediaTypeCaptor.getAllValues().get(0));
        assertNull(mediaTypeCaptor.getAllValues().get(1)); // 非 @Optimize → 不缓存 → 两次全量
    }

    @Test
    void writeBody_ctxNull_degradesToNoCache() throws Exception {
        HttpBodyConverter converter = jsonConverter();
        registry.converters.add(converter);
        // 不 set MappingResult → PathMappingContext.get() 返回 null → ctx 为 null → 降级不缓存

        write("a", optimizeReturnType);
        write("b", optimizeReturnType);

        ArgumentCaptor<MediaType> mediaTypeCaptor = ArgumentCaptor.forClass(MediaType.class);
        verify(converter, times(2)).canWrite(any(), any(), mediaTypeCaptor.capture(), any(), any(), any());
        assertNull(mediaTypeCaptor.getAllValues().get(0));
        assertNull(mediaTypeCaptor.getAllValues().get(1)); // ctx 为 null → 不缓存 → 两次全量
    }

    // ==================== 2a produces 分支并入缓存 ====================

    @Test
    void writeBody_optimizeMethodWithProduces_hitsCache() throws Exception {
        HttpBodyConverter firstConverter = mock(HttpBodyConverter.class);
        when(firstConverter.canWrite(any(), any(), any(), any(), any(), any())).thenReturn(false);
        HttpBodyConverter jsonConverter = jsonConverter();
        registry.converters.add(firstConverter);
        registry.converters.add(jsonConverter);
        setMatchedContext(optimizedProduceContext());

        write("a", optimizeReturnType);
        write("b", optimizeReturnType);

        // 第一次 miss：2a 反查遍历 first(false) + json(true) = 2 次 canWrite，选 json；
        // 第二次命中缓存：仅 json 二次 canWrite 1 次（跳过反查遍历）。
        ArgumentCaptor<MediaType> mediaTypeCaptor = ArgumentCaptor.forClass(MediaType.class);
        verify(jsonConverter, times(2)).canWrite(any(), any(), mediaTypeCaptor.capture(), any(), any(), any());
        List<MediaType> captured = mediaTypeCaptor.getAllValues();
        assertEquals(2, captured.size());
        // 2a 先定 mediaType（produces 协商出 concrete application/json）再反查 converter，
        // 与 2b（miss 时 canWrite 传 null）不同——此处两次都应是 concrete。
        assertEquals(MediaType.APPLICATION_JSON, captured.get(0));
        assertEquals(MediaType.APPLICATION_JSON, captured.get(1));
        verify(jsonConverter, times(2)).write(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void writeBody_optimizeMethodWithProduces_noConverter_silentNoWrite() throws Exception {
        HttpBodyConverter converter = mock(HttpBodyConverter.class);
        when(converter.canWrite(any(), any(), any(), any(), any(), any())).thenReturn(false);
        registry.converters.add(converter);
        setMatchedContext(optimizedProduceContext());

        write("a", optimizeReturnType);

        // produces 协商出 concrete mediaType 但无 converter 支持 → 静默不写
        // （与原 writeWithConverter 找不到 converter 的行为一致）。
        verify(converter, never()).write(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void writeBody_plainMethodWithProduces_degradesToNoCache() throws Exception {
        HttpBodyConverter firstConverter = mock(HttpBodyConverter.class);
        when(firstConverter.canWrite(any(), any(), any(), any(), any(), any())).thenReturn(false);
        HttpBodyConverter jsonConverter = jsonConverter();
        registry.converters.add(firstConverter);
        registry.converters.add(jsonConverter);
        setMatchedContext(plainProduceContext());

        write("a", plainReturnType);
        write("b", plainReturnType);

        // 非 @Optimize → 不缓存 → 每次 miss 反查：first(false) + json(true) 各 2 次
        verify(firstConverter, times(2)).canWrite(any(), any(), any(), any(), any(), any());
        verify(jsonConverter, times(2)).canWrite(any(), any(), any(), any(), any(), any());
        verify(jsonConverter, times(2)).write(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void writeBody_optimizeMethodWithProduces_acceptIncompatible_throws() throws Exception {
        HttpBodyConverter converter = jsonConverter();
        registry.converters.add(converter);
        setMatchedContext(optimizedProduceContext());
        requestHeaders.set("Accept", "text/plain");

        // Accept 与 produces 无交集 → findBestMatch 返回 null → 抛 HttpMessageNotWritableException
        assertThrows(HttpMessageNotWritableException.class, () -> write("a", optimizeReturnType));
    }
}
