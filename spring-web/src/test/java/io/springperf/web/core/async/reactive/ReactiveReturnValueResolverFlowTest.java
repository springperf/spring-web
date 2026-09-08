package io.springperf.web.core.async.reactive;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.async.stream.SseEmitter;
import io.springperf.web.core.async.stream.SseJsonEmitter;
import io.springperf.web.core.async.stream.StreamEmitter;
import io.springperf.web.core.async.stream.StreamSender;
import io.springperf.web.core.async.stream.StreamSenderFactory;
import io.springperf.web.core.async.stream.StreamJsonEmitter;
import io.springperf.web.core.async.stream.TextStreamEmitter;
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
import org.springframework.core.ReactiveAdapter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 补充 ReactiveReturnValueResolver 覆盖率：init 装配、supports 边界、
 * resolveReturnValue 的流式/DeferredResult 路径、createStreamEmitter 分支选择
 * 与 containMediaType 匹配失败路径。
 */
class ReactiveReturnValueResolverFlowTest {

    static {
        // Spring 6（master/SB3.5）的 ReactiveAdapterRegistry 默认注册 org.reactivestreams.Publisher；
        // Spring 5.3（SB2.7）默认不注册。此处对齐 master 行为，仅对测试的 shared registry 补充注册。
        org.springframework.core.ReactiveAdapterRegistry.getSharedInstance().registerReactiveType(
                org.springframework.core.ReactiveTypeDescriptor.multiValue(
                        org.reactivestreams.Publisher.class,
                        () -> new ReactiveReturnValueResolverFlowTest.NoopPublisher()),
                source -> (org.reactivestreams.Publisher<?>) source,
                publisher -> publisher);
    }

    private ReactiveReturnValueResolver resolver;
    private WebServerHttpRequest request;
    private RequestContext requestContext;
    private WebServerHttpResponse response;
    private AsyncSupportRegistry asyncSupportRegistry;
    private StreamSenderFactory streamSenderFactory;

    private HttpHeaders requestHeaders;
    private HttpHeaders responseHeaders;

    private final Map<RequestAttribute<?>, Object> attrs = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        resolver = new ReactiveReturnValueResolver();
        asyncSupportRegistry = mock(AsyncSupportRegistry.class);
        when(asyncSupportRegistry.getJsonConverter()).thenReturn(mock(JsonConverter.class));
        streamSenderFactory = mock(StreamSenderFactory.class);
        setField("asyncSupportRegistry", asyncSupportRegistry);
        setField("streamSenderFactory", streamSenderFactory);
        setField("adapterRegistry", ReactiveAdapterRegistry.getSharedInstance());

        request = mock(WebServerHttpRequest.class);
        response = mock(WebServerHttpResponse.class);
        requestContext = mock(RequestContext.class);
        requestHeaders = new HttpHeaders();
        responseHeaders = new HttpHeaders();
        when(request.getRequestContext()).thenReturn(requestContext);
        when(request.getHeaders()).thenReturn(requestHeaders);
        when(response.getHeaders()).thenReturn(responseHeaders);
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

    private static MethodParameter param(String methodName, Class<?> type) throws Exception {
        return new MethodParameter(TypeHolder.class.getMethod(methodName, type), 0);
    }

    @SuppressWarnings("unused")
    static class TypeHolder {
        public void handler(org.reactivestreams.Publisher<String> p) {}
        public void handler(String s) {}
        public void handler(ResponseEntity<org.reactivestreams.Publisher<String>> p) {}
        public void handler(CompletableFuture<String> f) {}
    }

    private PathMappingContext mockMapping() throws Exception {
        Object bean = new Object();
        HandlerMethod hm = new HandlerMethod(bean, Object.class.getDeclaredMethod("toString"));
        return new PathMappingContext(hm, Collections.emptyList(), "/rx");
    }

    /* ==================== initWithWebContext ==================== */

    @Test
    void initWithWebContext_usesDefaultsWhenNoBeans() throws Exception {
        ReactiveReturnValueResolver fresh = new ReactiveReturnValueResolver();
        WebContext wc = mock(WebContext.class);
        when(wc.getWebComponentWithDefault(eq(AsyncSupportRegistry.class), any(AsyncSupportRegistry.class)))
                .thenReturn(asyncSupportRegistry);
        when(wc.getWebComponentWithDefault(eq(StreamSenderFactory.class), any(StreamSenderFactory.class)))
                .thenReturn(streamSenderFactory);
        when(wc.getBeanFromCtx(ReactiveAdapterRegistry.class)).thenReturn(null);

        fresh.initWithWebContext(wc);

        assertNotNull(readField(fresh, "asyncSupportRegistry"));
        assertNotNull(readField(fresh, "streamSenderFactory"));
        assertNotNull(readField(fresh, "adapterRegistry"), "无显式 adapter 时应使用共享实例");
    }

    private static Object readField(Object target, String name) throws Exception {
        java.lang.reflect.Field f = ReactiveReturnValueResolver.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /* ==================== supports 边界 ==================== */

    @Test
    void supportsReturnType_adapterRegistryNull_returnsFalse() throws Exception {
        setField("adapterRegistry", null);
        assertFalse(resolver.supportsReturnType(param("handler", org.reactivestreams.Publisher.class), null));
    }

    @Test
    void supportsReturnType_responseEntityGeneric_resolvesBody() throws Exception {
        assertTrue(resolver.supportsReturnType(
                param("handler", ResponseEntity.class), null));
    }

    @Test
    void supportsReturnValue_responseEntityBody_reactive() {
        org.reactivestreams.Publisher<String> p = simplePublisher();
        assertTrue(resolver.supportsReturnValue(ResponseEntity.ok(p), request, response));
    }

    @Test
    void supportsReturnValue_responseEntityNullBody_false() {
        assertFalse(resolver.supportsReturnValue(ResponseEntity.ok(null), request, response));
    }

    /* ==================== createStreamEmitter 分支 ==================== */

    @Test
    void createStreamEmitter_customType_usesConstructor() throws Exception {
        @SuppressWarnings("unchecked")
        Constructor<? extends StreamEmitter> ctor = (Constructor<? extends StreamEmitter>) (Constructor<?>)
                SseEmitter.class.getConstructor(Long.class);
        ReactiveConfig config = new ReactiveConfig(SseEmitter.class, ctor, 150, 50, 5000L);
        ReactiveAdapter adapter = ReactiveAdapterRegistry.getSharedInstance()
                .getAdapter(org.reactivestreams.Publisher.class);
        StreamEmitter emitter = resolver.createStreamEmitter(config, adapter, String.class, request, response);
        assertNotNull(emitter);
        assertTrue(emitter instanceof SseEmitter);
    }

    @Test
    void createStreamEmitter_sseMediaType_returnsSseJsonEmitter() throws Exception {
        responseHeaders.setContentType(MediaType.TEXT_EVENT_STREAM);
        ReactiveAdapter adapter = ReactiveAdapterRegistry.getSharedInstance()
                .getAdapter(org.reactivestreams.Publisher.class);
        ReactiveConfig config = ReactiveConfig.DEFAULT;
        StreamEmitter emitter = resolver.createStreamEmitter(config, adapter, String.class, request, response);
        assertTrue(emitter instanceof SseJsonEmitter);
    }

    @Test
    void createStreamEmitter_charSequence_returnsTextStreamEmitter() throws Exception {
        ReactiveAdapter adapter = ReactiveAdapterRegistry.getSharedInstance()
                .getAdapter(org.reactivestreams.Publisher.class);
        ReactiveConfig config = ReactiveConfig.DEFAULT;
        StreamEmitter emitter = resolver.createStreamEmitter(config, adapter, String.class, request, response);
        assertTrue(emitter instanceof TextStreamEmitter);
    }

    @Test
    void createStreamEmitter_streamJsonMediaType_returnsStreamJsonEmitter() throws Exception {
        requestHeaders.setAccept(Collections.singletonList(MediaType.parseMediaType("application/stream+json")));
        ReactiveAdapter adapter = ReactiveAdapterRegistry.getSharedInstance()
                .getAdapter(org.reactivestreams.Publisher.class);
        ReactiveConfig config = ReactiveConfig.DEFAULT;
        // 元素类型必须非 CharSequence，否则优先命中 TextStreamEmitter 分支
        StreamEmitter emitter = resolver.createStreamEmitter(config, adapter, Datum.class, request, response);
        assertTrue(emitter instanceof StreamJsonEmitter);
        assertEquals(MediaType.parseMediaType("application/stream+json"),
                response.getHeaders().getContentType(), "accept 命中时响应应回写具体的 Content-Type");
    }

    public static final class Datum {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /* ==================== 单值适配器（自定义注册，模拟 CompletableFuture 语义） ==================== */

    static final class SingleValue {
        final String value;

        SingleValue(String value) {
            this.value = value;
        }
    }

    private void setSingleValueAdapter() {
        ReactiveAdapterRegistry registry = new ReactiveAdapterRegistry();
        registry.registerReactiveType(
                org.springframework.core.ReactiveTypeDescriptor.singleRequiredValue(SingleValue.class),
                obj -> subscriber -> {
                    subscriber.onSubscribe(new org.reactivestreams.Subscription() {
                        boolean done;

                        @Override
                        public void request(long n) {
                            if (!done) {
                                done = true;
                                subscriber.onNext(((SingleValue) obj).value);
                                subscriber.onComplete();
                            }
                        }

                        @Override
                        public void cancel() {
                        }
                    });
                },
                publisher -> null);
        try {
            setField("adapterRegistry", registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createStreamEmitter_singleValue_returnsNull() throws Exception {
        ReactiveAdapterRegistry registry = new ReactiveAdapterRegistry();
        registry.registerReactiveType(
                org.springframework.core.ReactiveTypeDescriptor.singleRequiredValue(SingleValue.class),
                obj -> subscriber -> {
                    subscriber.onSubscribe(new org.reactivestreams.Subscription() {
                        @Override
                        public void request(long n) {
                        }

                        @Override
                        public void cancel() {
                        }
                    });
                }, publisher -> null);
        ReactiveAdapter adapter = registry.getAdapter(SingleValue.class);
        ReactiveConfig config = ReactiveConfig.DEFAULT;
        StreamEmitter emitter = resolver.createStreamEmitter(config, adapter, String.class, request, response);
        assertNull(emitter);
    }

    /* ==================== resolveReturnValue 流程 ==================== */

    @Test
    void resolveReturnValue_singleValue_usesDeferredResult() throws Exception {
        setSingleValueAdapter();
        requestHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        SingleValue value = new SingleValue("ok");

        resolver.resolveReturnValue(value,
                param("handler", org.reactivestreams.Publisher.class), request, response);

        verify(asyncSupportRegistry).startDeferredResultProcessing(any(), any(), any());
    }

    @Test
    void resolveReturnValue_responseEntityUnwrapsStatusAndHeaders() throws Exception {
        setSingleValueAdapter();
        SingleValue value = new SingleValue("ok");
        ResponseEntity<SingleValue> responseEntity = ResponseEntity.status(202)
                .header("X-Custom", "v").body(value);

        resolver.resolveReturnValue(responseEntity,
                param("handler", ResponseEntity.class), request, response);

        verify(response).setStatusCode(org.springframework.http.HttpStatus.ACCEPTED);
        verify(asyncSupportRegistry).startDeferredResultProcessing(any(), any(), any());
    }

    @Test
    void resolveReturnValue_stream_usesStreamEmitter() throws Exception {
        when(streamSenderFactory.create(any(), any())).thenReturn(mock(StreamSender.class));
        requestHeaders.setAccept(Collections.singletonList(MediaType.TEXT_EVENT_STREAM));
        responseHeaders.setContentType(MediaType.TEXT_EVENT_STREAM);
        org.reactivestreams.Publisher<String> p = simplePublisher();

        resolver.resolveReturnValue(p,
                param("handler", org.reactivestreams.Publisher.class), request, response);

        verify(streamSenderFactory).create(any(), any());
    }

    private static org.reactivestreams.Publisher<String> simplePublisher() {
        return subscriber -> subscriber.onSubscribe(new org.reactivestreams.Subscription() {
            private boolean done;

            @Override
            public void request(long n) {
                if (!done) {
                    done = true;
                    subscriber.onNext("a");
                    subscriber.onNext("b");
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
            }
        });
    }

    /* ==================== containMediaType 匹配失败 ==================== */

    @Test
    void containMediaType_noMatch_returnsFalse() {
        requestHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        assertFalse(resolver.containMediaType(MediaType.TEXT_EVENT_STREAM, request, response));
    }

    @Test
    void getReactiveConfig_mappingWithoutAnnotation_returnsDefault() throws Exception {
        PathMappingContext ctx = mockMapping();
        MappingResult.set(request, MappingResult.matched(ctx));
        assertSame(ReactiveConfig.DEFAULT, resolver.getReactiveConfig(request));
    }

    /* ==================== getConstructorArgs ==================== */

    @Test
    void getConstructorArgs_buildsArgumentArray() throws Exception {
        @SuppressWarnings("unchecked")
        Constructor<? extends StreamEmitter> ctor = (Constructor<? extends StreamEmitter>) (Constructor<?>)
                SseJsonEmitter.class.getConstructor(Long.class, JsonConverter.class);
        ReactiveConfig config = new ReactiveConfig(SseJsonEmitter.class, ctor, 150, 50, 5000L);
        Object[] args = resolver.getConstructorArgs(ctor, config, request);
        assertEquals(2, args.length);
        assertEquals(5000L, args[0]);
        assertNotNull(args[1]);
    }

    /** 注册 org.reactivestreams.Publisher adapter 时用的空 Publisher。 */
    static final class NoopPublisher implements org.reactivestreams.Publisher<Object> {
        @Override
        public void subscribe(org.reactivestreams.Subscriber<? super Object> subscriber) {
            subscriber.onComplete();
        }
    }
}