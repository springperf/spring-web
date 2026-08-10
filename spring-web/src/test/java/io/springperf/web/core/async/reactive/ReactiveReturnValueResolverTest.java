package io.springperf.web.core.async.reactive;

import io.springperf.web.annotation.ReactiveSupport;
import io.springperf.web.core.async.stream.SseEmitter;
import io.springperf.web.core.async.stream.StreamEmitter;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归用例：{@code getReactiveConfig} 在 {@code reactiveConfig == null} 时
 * 误用仍为 null 的变量取 {@code streamEmitterType}，导致带 {@link ReactiveSupport}
 * 注解的 handler 首次请求必 NPE。修复后应返回正常 {@link ReactiveConfig}。
 */
class ReactiveReturnValueResolverTest {

    /** 仅有无受支持构造器（String 参数）的 StreamEmitter 子类，用于测试越界防御 */
    abstract static class UnsupportedEmitter extends StreamEmitter<Object> {
        @SuppressWarnings("unused")
        public UnsupportedEmitter(String unused) {
            super();
        }
    }

    private ReactiveReturnValueResolver resolver;
    private WebServerHttpRequest request;
    private RequestContext requestContext;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resolver = new ReactiveReturnValueResolver();
        request = mock(WebServerHttpRequest.class);
        requestContext = mock(RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(any(RequestAttribute.class))).thenReturn(null);
    }

    private ReactiveSupport reactiveSupportAnnotation() {
        return (ReactiveSupport) Proxy.newProxyInstance(
                ReactiveSupport.class.getClassLoader(),
                new Class<?>[]{ReactiveSupport.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "streamEmitterType":
                            return SseEmitter.class;
                        case "highWaterMark":
                            return 100;
                        case "lowWaterMark":
                            return 20;
                        case "timeout":
                            return 5000L;
                        case "annotationType":
                            return ReactiveSupport.class;
                        default:
                            return method.getDefaultValue();
                    }
                });
    }

    @Test
    void getReactiveConfig_withReactiveSupport_doesNotNpe() {
        // PathMappingContext 继承 MappingHandlerMethod；mock 它返回 @ReactiveSupport 注解
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.get(ReactiveReturnValueResolver.MAPPING_CACHE_KEY)).thenReturn(null);
        when(ctx.getMethodAndClassAnnotation(ReactiveSupport.class))
                .thenReturn(reactiveSupportAnnotation());
        when(requestContext.getAttribute(any(RequestAttribute.class)))
                .thenReturn(MappingResult.matched(ctx));

        ReactiveConfig config = resolver.getReactiveConfig(request);
        assertNotNull(config, "带 @ReactiveSupport 时不应返回 null");
        assertSame(SseEmitter.class, config.getStreamEmitterType());
        assertNotNull(config.getStreamEmitterConstructor(), "应解析出 SseEmitter 构造器");
        assertEquals(100, config.getHighWaterMark());
        assertEquals(20, config.getLowWaterMark());
        assertEquals(5000L, config.getTimeout());
    }

    @Test
    void getReactiveConfig_withoutMapping_returnsDefault() {
        // 无映射上下文时返回默认配置（不抛异常）
        ReactiveConfig config = resolver.getReactiveConfig(request);
        assertSame(ReactiveConfig.DEFAULT, config);
    }

    @Test
    void selectBestConstructor_unknownType_throwsInsteadOfIoobe() {
        // 自定义 streamEmitterType 无受支持构造器时，应给出明确错误而非越界
        assertThrows(IllegalStateException.class,
                () -> resolver.selectBestConstructor(UnsupportedEmitter.class));
    }
}
