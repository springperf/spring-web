package io.springperf.web.core.async.stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServerHttpResponse;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.async.AsyncSupportUtils;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

@ExtendWith(MockitoExtension.class)
class StreamEmitterReturnValueResolverTest {

    @Mock
    WebServerHttpRequest request;
    @Mock
    WebServerHttpResponse response;
    @Mock
    WebContext webContext;
    @Mock
    AsyncSupportRegistry asyncSupportRegistry;
    @Mock
    MappingHandlerMethod mappingContext;

    private StreamEmitterReturnValueResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        resolver = new StreamEmitterReturnValueResolver();
        Field f = StreamEmitterReturnValueResolver.class.getSuperclass().getDeclaredField("asyncSupportRegistry");
        f.setAccessible(true);
        f.set(resolver, asyncSupportRegistry);
    }

    @Test
    void supportsReturnType_streamEmitterType() throws Exception {
        assertTrue(resolver.supportsReturnType(param("streamEmitterParam", StreamEmitter.class), mappingContext));
    }

    @Test
    void supportsReturnType_concreteSubclass() throws Exception {
        assertTrue(resolver.supportsReturnType(param("sseEmitterParam", SseEmitter.class), mappingContext));
    }

    @Test
    void supportsReturnType_responseEntityWrappedStreamEmitter() throws Exception {
        assertTrue(resolver.supportsReturnType(param("responseEntityStreamEmitter", ResponseEntity.class),
                mappingContext));
    }

    @Test
    void supportsReturnType_notSupported() throws Exception {
        assertFalse(resolver.supportsReturnType(param("stringParam", String.class), mappingContext));
    }

    @Test
    void supportsReturnValue_streamEmitterInstance() {
        assertTrue(resolver.supportsReturnValue(new StreamEmitter() {
            @Override
            public void encode(Object data, java.io.OutputStream out) {
            }

            @Override
            protected void extendResponse(ServerHttpResponse response) {
            }
        }, null, null));
    }

    @Test
    void supportsReturnValue_responseEntityBody() {
        StreamEmitter emitter = new StreamEmitter() {
            @Override
            public void encode(Object data, java.io.OutputStream out) {
            }

            @Override
            protected void extendResponse(ServerHttpResponse response) {
            }
        };
        assertTrue(resolver.supportsReturnValue(ResponseEntity.ok(emitter), null, null));
    }

    @Test
    void supportsReturnValue_notSupported() {
        assertFalse(resolver.supportsReturnValue("notAnEmitter", null, null));
    }

    @Test
    void initWithWebContext_loadsDefaultFactory() {
        // BaseAsyncReturnValueResolver.initWithWebContext 先调用了 AsyncSupportRegistry
        when(webContext.getWebComponentWithDefault(eq(AsyncSupportRegistry.class), any(AsyncSupportRegistry.class)))
                .thenReturn(asyncSupportRegistry);
        when(webContext.getWebComponentWithDefault(eq(StreamSenderFactory.class), any(StreamSenderFactory.class)))
                .thenReturn(new DefaultStreamSenderFactory());

        resolver.initWithWebContext(webContext);

        assertNotNull(resolver);
    }

    @Test
    void resolveReturnValue_unwrapsResponseEntity() throws Exception {
        // 设置 streamSenderFactory 通过反射，避免依赖 StaticUtil 调用链
        StreamSenderFactory factory = mock(StreamSenderFactory.class);
        when(factory.create(any(), any())).thenReturn(mock(StreamSender.class));
        Field factoryField = StreamEmitterReturnValueResolver.class.getDeclaredField("streamSenderFactory");
        factoryField.setAccessible(true);
        factoryField.set(resolver, factory);

        StreamEmitter emitter = new StreamEmitter() {
            @Override
            public void encode(Object data, java.io.OutputStream out) {
            }

            @Override
            protected void extendResponse(ServerHttpResponse response) {
            }
        };
        ResponseEntity<StreamEmitter> entity = ResponseEntity.status(HttpStatus.ACCEPTED).header("X-Custom", "value")
                .body(emitter);
        HttpHeaders respHeaders = new HttpHeaders();
        when(response.getHeaders()).thenReturn(respHeaders);

        // 异步上下文必须可用：缺桩时 requestContext 为 null，原用例的 assertThrows(Exception.class) 把 NPE 一并吞了
        RequestContext requestContext = mock(RequestContext.class);
        PerfAsyncWebRequest asyncWebRequest = mock(PerfAsyncWebRequest.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE)).thenReturn(asyncWebRequest);

        resolver.resolveReturnValue(entity, null, request, response);

        // 验证 ResponseEntity 的状态码和 headers 已被设置，且 body 交给了 sender 工厂
        verify(response).setStatusCode(HttpStatus.ACCEPTED);
        assertNotNull(respHeaders.getFirst("X-Custom"));
        verify(factory).create(eq(emitter), eq(asyncWebRequest));
    }

    @Test
    void resolveReturnValue_headRequest_doesNotStartStream() throws Exception {
        // HEAD 的响应在 preInitializeEmitter 的 flush(true) 里就以「仅响应头」收尾，
        // 编码器随即复位为 INIT。此时若仍建 StreamSender，drain 的第一帧内容会被编码器拒绝
        // （EncoderException: unexpected message type: DefaultHttpContent, state: 0），
        // 正常收尾被误报成流错误。契约：HEAD 不建发送器、不启动异步。
        StreamSenderFactory factory = mock(StreamSenderFactory.class);
        Field factoryField = StreamEmitterReturnValueResolver.class.getDeclaredField("streamSenderFactory");
        factoryField.setAccessible(true);
        factoryField.set(resolver, factory);
        // 下方打桩只为让「未修复实现」走到 create() 再失败；修复后它们不会被使用，故 lenient
        lenient().when(factory.create(any(), any())).thenReturn(mock(StreamSender.class));
        lenient().when(request.getRequestContext()).thenReturn(mock(RequestContext.class));
        when(response.isHeadRequest()).thenReturn(true);

        StreamEmitter emitter = new StreamEmitter() {
            @Override
            public void encode(Object data, java.io.OutputStream out) {
            }

            @Override
            protected void extendResponse(ServerHttpResponse response) {
            }
        };

        resolver.resolveReturnValue(emitter, null, request, response);

        verify(factory, never()).create(any(), any());
        // initializeWithStreamSender 未被调用：emitter 里没有接入发送器
        assertNull(emitter.streamSender);
        // 响应仍必须被扩展并提交（HEAD 要回响应头，content-type 等元数据不能丢）
        verify(response).flush(true);
    }

    // ===== helpers =====

    private MethodParameter param(String methodName, Class<?> paramType) throws Exception {
        for (Method m : getClass().getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                return new MethodParameter(m, 0);
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    @SuppressWarnings("unused")
    public void streamEmitterParam(StreamEmitter e) {
    }

    @SuppressWarnings("unused")
    public void sseEmitterParam(SseEmitter e) {
    }

    @SuppressWarnings("unused")
    public void responseEntityStreamEmitter(ResponseEntity<StreamEmitter> e) {
    }

    @SuppressWarnings("unused")
    public void stringParam(String s) {
    }
}
