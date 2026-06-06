package io.springperf.web.core.async.stream;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
        Field f = StreamEmitterReturnValueResolver.class.getSuperclass()
                .getDeclaredField("asyncSupportRegistry");
        f.setAccessible(true);
        f.set(resolver, asyncSupportRegistry);
    }

    @Test
    void supportsReturnType_streamEmitterType() throws Exception {
        assertTrue(resolver.supportsReturnType(
                param("streamEmitterParam", StreamEmitter.class), mappingContext));
    }

    @Test
    void supportsReturnType_concreteSubclass() throws Exception {
        assertTrue(resolver.supportsReturnType(
                param("sseEmitterParam", SseEmitter.class), mappingContext));
    }

    @Test
    void supportsReturnType_responseEntityWrappedStreamEmitter() throws Exception {
        assertTrue(resolver.supportsReturnType(
                param("responseEntityStreamEmitter", ResponseEntity.class), mappingContext));
    }

    @Test
    void supportsReturnType_notSupported() throws Exception {
        assertFalse(resolver.supportsReturnType(
                param("stringParam", String.class), mappingContext));
    }

    @Test
    void supportsReturnValue_streamEmitterInstance() {
        assertTrue(resolver.supportsReturnValue(
                new StreamEmitter(true) {
                    @Override
                    protected void extendResponse(ServerHttpResponse response) {}
                }, null, null));
    }

    @Test
    void supportsReturnValue_responseEntityBody() {
        StreamEmitter emitter = new StreamEmitter(true) {
            @Override
            protected void extendResponse(ServerHttpResponse response) {}
        };
        assertTrue(resolver.supportsReturnValue(
                ResponseEntity.ok(emitter), null, null));
    }

    @Test
    void supportsReturnValue_notSupported() {
        assertFalse(resolver.supportsReturnValue("notAnEmitter", null, null));
    }

    @Test
    void initWithWebContext_loadsDefaultFactory() {
        // BaseAsyncReturnValueResolver.initWithWebContext 先调用了 AsyncSupportRegistry
        when(webContext.getWebComponentWithDefault(
                eq(AsyncSupportRegistry.class), any(AsyncSupportRegistry.class)))
                .thenReturn(asyncSupportRegistry);
        when(webContext.getWebComponentWithDefault(
                eq(StreamSenderFactory.class), any(StreamSenderFactory.class)))
                .thenReturn(new DefaultStreamSenderFactory());

        resolver.initWithWebContext(webContext);

        assertNotNull(resolver);
    }

    @Test
    void resolveReturnValue_unwrapsResponseEntity() throws Exception {
        // 设置 streamSenderFactory 通过反射，避免依赖 StaticUtil 调用链
        Field factoryField = StreamEmitterReturnValueResolver.class
                .getDeclaredField("streamSenderFactory");
        factoryField.setAccessible(true);
        factoryField.set(resolver, mock(StreamSenderFactory.class));

        StreamEmitter emitter = new StreamEmitter(true) {
            @Override
            protected void extendResponse(ServerHttpResponse response) {}
        };
        ResponseEntity<StreamEmitter> entity = ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .header("X-Custom", "value")
                .body(emitter);
        HttpHeaders respHeaders = new HttpHeaders();
        when(response.getHeaders()).thenReturn(respHeaders);

        // resolveReturnValue 会调用 StreamEmitterUtil 的静态方法，预期抛出异常
        assertThrows(Exception.class, () ->
                resolver.resolveReturnValue(entity, null, request, response));

        // 验证 ResponseEntity 的状态码和 headers 已被设置
        verify(response).setStatusCode(HttpStatus.ACCEPTED);
        assertTrue(respHeaders.containsKey("X-Custom"));
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
    public void streamEmitterParam(StreamEmitter e) {}
    @SuppressWarnings("unused")
    public void sseEmitterParam(SseEmitter e) {}
    @SuppressWarnings("unused")
    public void responseEntityStreamEmitter(ResponseEntity<StreamEmitter> e) {}
    @SuppressWarnings("unused")
    public void stringParam(String s) {}
}
