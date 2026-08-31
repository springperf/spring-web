package io.springperf.web.support.codec.interceptor;

import io.springperf.web.context.WebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.codec.interceptor.WebComponentControllerAdviceBean;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupportHttpBodyCodecInterceptorRegistryTest {

    @Test
    void constructor_doesNotThrow() {
        assertDoesNotThrow(SupportHttpBodyCodecInterceptorRegistry::new);
    }

    @Test
    void initWithWebContext_scansAdviceAndWrapsAsInterceptors() {
        // 核心逻辑：initCodecInterceptors() 扫描 @ControllerAdvice 中 RequestBodyAdvice / ResponseBodyAdvice
        // 子类，包装为 RequestBodyAdviceCodecInterceptor / ResponseBodyAdviceCodecInterceptor 并注册。
        // 使用真实 AnnotationConfigApplicationContext 让 Spring 的 findAnnotatedBeans 正常工作。
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(TestRequestBodyAdvice.class);
            ctx.registerBean(TestResponseBodyAdvice.class);
            ctx.refresh();

            WebContext webContext = mock(WebContext.class);
            lenient().when(webContext.getCtx()).thenReturn(ctx);

            SupportHttpBodyCodecInterceptorRegistry registry = new SupportHttpBodyCodecInterceptorRegistry();
            registry.initWithWebContext(webContext);

            // 注：注册的组件是 WebComponentControllerAdviceBean（包装类型），而非 advice 本身，
            // 需遍历容器验证包装了对应 CodecInterceptor
            assertTrue(hasWrappedInterceptor(registry, RequestBodyAdviceCodecInterceptor.class),
                    "RequestBodyAdvice 应被包装为 RequestBodyAdviceCodecInterceptor 注册");
            assertTrue(hasWrappedInterceptor(registry, ResponseBodyAdviceCodecInterceptor.class),
                    "ResponseBodyAdvice 应被包装为 ResponseBodyAdviceCodecInterceptor 注册");
        }
    }

    private static boolean hasWrappedInterceptor(io.springperf.web.context.WebComponentContainer container,
                                                 Class<?> interceptorType) {
        for (WebComponent component : container.getWebComponents(WebComponent.class)) {
            if (component instanceof WebComponentControllerAdviceBean) {
                Object real = ((WebComponentControllerAdviceBean<?>) component).getComponent();
                if (real != null && interceptorType.isAssignableFrom(real.getClass())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void wrapperDelegatesToRequestBodyAdvice() throws Exception {
        TestRequestBodyAdvice advice = new TestRequestBodyAdvice();
        RequestBodyAdviceCodecInterceptor interceptor = new RequestBodyAdviceCodecInterceptor(advice);

        io.springperf.web.core.codec.HttpBodyConverter converter =
                mock(io.springperf.web.core.codec.HttpBodyConverter.class);
        when(converter.getConverterClass()).thenReturn((Class) HttpMessageConverter.class);
        MethodParameter param = mock(MethodParameter.class);

        assertTrue(interceptor.supportBodyRead(param, String.class, converter), "advice.supports=true 时应透传");
        HttpInputMessage in = mock(HttpInputMessage.class);
        assertEquals(HttpInputMessage.class.getName(),
                interceptor.beforeBodyRead(in, param, String.class, converter).toString(),
                "beforeBodyRead 应委托给 advice 实现");
        assertEquals("after-body",
                interceptor.afterBodyRead("raw", in, param, String.class, converter));
        assertEquals("empty-body",
                interceptor.handleEmptyBodyRead(null, in, param, String.class, converter));
    }

    private static void assertDoesNotThrow(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            throw new org.opentest4j.AssertionFailedError("不应抛异常", e);
        }
    }

    @ControllerAdvice
    static class TestRequestBodyAdvice implements RequestBodyAdvice {
        @Override
        public boolean supports(MethodParameter methodParameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
            return true;
        }

        @Override
        public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
                                               Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
            return new HttpInputMessage() {
                @Override
                public java.io.InputStream getBody() {
                    return new ByteArrayInputStream(new byte[0]);
                }

                @Override
                public org.springframework.http.HttpHeaders getHeaders() {
                    return new org.springframework.http.HttpHeaders();
                }

                @Override
                public String toString() {
                    return HttpInputMessage.class.getName();
                }
            };
        }

        @Override
        public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                    Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
            return "after-body";
        }

        @Override
        public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                      Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
            return "empty-body";
        }
    }

    @ControllerAdvice
    static class TestResponseBodyAdvice implements ResponseBodyAdvice<Object> {
        @Override
        public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
            return true;
        }

        @Override
        public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                      org.springframework.http.MediaType selectedContentType,
                                      Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                      org.springframework.http.server.ServerHttpRequest request,
                                      org.springframework.http.server.ServerHttpResponse response) {
            return body;
        }
    }
}