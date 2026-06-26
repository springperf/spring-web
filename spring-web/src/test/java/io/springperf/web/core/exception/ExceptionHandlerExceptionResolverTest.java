package io.springperf.web.core.exception;

import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExceptionHandlerExceptionResolverTest {

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    RequestContext requestContext;

    // ---- ExceptionArgumentResolverProvider ----

    @Test
    void exceptionArgumentResolverProvider_supportsThrowable() throws Exception {
        ExceptionHandlerExceptionResolver.ExceptionArgumentResolverProvider provider =
                new ExceptionHandlerExceptionResolver.ExceptionArgumentResolverProvider();

        Method sampleMethod = DummyHandler.class.getMethod("handleException", Throwable.class);
        MethodParameter throwableParam = new MethodParameter(sampleMethod, 0);
        MappingHandlerMethod mappingHandlerMethod = new MappingHandlerMethod(new DummyHandler(), sampleMethod);

        assertTrue(provider.supports(throwableParam, mappingHandlerMethod));
    }

    @Test
    void exceptionArgumentResolverProvider_doesNotSupportNonThrowable() throws Exception {
        ExceptionHandlerExceptionResolver.ExceptionArgumentResolverProvider provider =
                new ExceptionHandlerExceptionResolver.ExceptionArgumentResolverProvider();

        Method sampleMethod = DummyHandler.class.getMethod("handleString", String.class);
        MethodParameter stringParam = new MethodParameter(sampleMethod, 0);
        MappingHandlerMethod mappingHandlerMethod = new MappingHandlerMethod(new DummyHandler(), sampleMethod);

        assertFalse(provider.supports(stringParam, mappingHandlerMethod));
    }

    @Test
    void exceptionArgumentResolverProvider_getResolver_returnsResolver() throws Exception {
        ExceptionHandlerExceptionResolver.ExceptionArgumentResolverProvider provider =
                new ExceptionHandlerExceptionResolver.ExceptionArgumentResolverProvider();

        Method sampleMethod = DummyHandler.class.getMethod("handleException", Throwable.class);
        MethodParameter param = new MethodParameter(sampleMethod, 0);

        StaticArgumentResolver result = provider.getResolver(param, null, null);
        assertNotNull(result);
    }

    @Test
    void exceptionArgumentResolverProvider_getResolver_resolvesFromRequestContext() throws Exception {
        ExceptionHandlerExceptionResolver.ExceptionArgumentResolverProvider provider =
                new ExceptionHandlerExceptionResolver.ExceptionArgumentResolverProvider();

        Method sampleMethod = DummyHandler.class.getMethod("handleException", Throwable.class);
        MethodParameter param = new MethodParameter(sampleMethod, 0);
        StaticArgumentResolver resolver = provider.getResolver(param, null, null);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(ExceptionHandlerExceptionResolver.class.getName() + "_KEY"))
                .thenReturn(new RuntimeException("test"));

        Object result = resolver.resolveArgument(request, response);
        assertTrue(result instanceof RuntimeException);
        assertEquals("test", ((RuntimeException) result).getMessage());
    }

    // ---- isExplicitRseHandler ----

    @Test
    void isExplicitRseHandler_rsExactMatch_returnsTrue() throws Exception {
        MappingHandlerMethod handlerMethod = handlerOn(new RseExplicitHandler(), "handleRse");

        assertTrue(ExceptionHandlerExceptionResolver.isExplicitRseHandler(handlerMethod));
    }

    @Test
    void isExplicitRseHandler_rseSubclass_returnsTrue() throws Exception {
        MappingHandlerMethod handlerMethod = handlerOn(new RseExplicitHandler(), "handleSubRse");

        assertTrue(ExceptionHandlerExceptionResolver.isExplicitRseHandler(handlerMethod));
    }

    @Test
    void isExplicitRseHandler_mixedWithRse_returnsTrue() throws Exception {
        MappingHandlerMethod handlerMethod = handlerOn(new RseExplicitHandler(), "handleRseAndIae");

        assertTrue(ExceptionHandlerExceptionResolver.isExplicitRseHandler(handlerMethod));
    }

    @Test
    void isExplicitRseHandler_throwableParent_returnsFalse() throws Exception {
        MappingHandlerMethod handlerMethod = handlerOn(new RseExplicitHandler(), "handleThrowable");

        assertFalse(ExceptionHandlerExceptionResolver.isExplicitRseHandler(handlerMethod));
    }

    @Test
    void isExplicitRseHandler_runtimeExceptionParent_returnsFalse() throws Exception {
        MappingHandlerMethod handlerMethod = handlerOn(new RseExplicitHandler(), "handleRuntimeException");

        assertFalse(ExceptionHandlerExceptionResolver.isExplicitRseHandler(handlerMethod));
    }

    @Test
    void isExplicitRseHandler_exceptionParent_returnsFalse() throws Exception {
        MappingHandlerMethod handlerMethod = handlerOn(new RseExplicitHandler(), "handleException");

        assertFalse(ExceptionHandlerExceptionResolver.isExplicitRseHandler(handlerMethod));
    }

    @Test
    void isExplicitRseHandler_iaeUnrelated_returnsFalse() throws Exception {
        MappingHandlerMethod handlerMethod = handlerOn(new RseExplicitHandler(), "handleIae");

        assertFalse(ExceptionHandlerExceptionResolver.isExplicitRseHandler(handlerMethod));
    }

    // ---- resolveException integration scenarios ----

    @Test
    void resolveException_rseWithThrowableHandler_skipsToNextResolver() throws Exception {
        ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();

        // 模拟两个 advice：第一个用 Throwable 兜底（应跳过 RSE），第二个显式声明 RSE
        ExceptionHandlerAdvice throwableAdvice = mockAdvice("handleThrowable");
        ExceptionHandlerAdvice rseAdvice = mockAdvice("handleRse");
        resolver.exceptionHandlerAdvices.add(throwableAdvice);
        resolver.exceptionHandlerAdvices.add(rseAdvice);

        when(request.getRequestContext()).thenReturn(requestContext);

        ResponseStatusException rse = new ResponseStatusException(HttpStatus.NOT_FOUND);
        boolean handled = resolver.resolveException(request, response, null, rse);

        // 验证跳过 throwableAdvice，被 rseAdvice 处理
        assertTrue(handled);
        verify(rseAdvice).resolveHandlerMethod(rse);
    }

    @Test
    void resolveException_nonRseWithThrowableHandler_handlesNormally() throws Exception {
        ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();

        ExceptionHandlerAdvice throwableAdvice = mockAdvice("handleThrowable");
        resolver.exceptionHandlerAdvices.add(throwableAdvice);

        when(request.getRequestContext()).thenReturn(requestContext);

        RuntimeException normalEx = new RuntimeException("business error");
        boolean handled = resolver.resolveException(request, response, null, normalEx);

        // 非 RSE 异常不受影响，正常被 throwableAdvice 处理
        assertTrue(handled);
        verify(throwableAdvice).resolveHandlerMethod(normalEx);
    }

    @Test
    void resolveException_rseOnlyExplicitHandler_handled() throws Exception {
        ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();

        ExceptionHandlerAdvice rseAdvice = mockAdvice("handleRse");
        resolver.exceptionHandlerAdvices.add(rseAdvice);

        when(request.getRequestContext()).thenReturn(requestContext);

        ResponseStatusException rse = new ResponseStatusException(HttpStatus.NOT_FOUND);
        boolean handled = resolver.resolveException(request, response, null, rse);

        assertTrue(handled);
        verify(rseAdvice).resolveHandlerMethod(rse);
    }

    @Test
    void resolveException_rseAllHandlersSkip_returnsFalse() throws Exception {
        ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();

        // 所有 advice 都只有 Throwable 兜底（应全部跳过 RSE）
        ExceptionHandlerAdvice advice1 = mockAdvice("handleThrowable");
        ExceptionHandlerAdvice advice2 = mockAdvice("handleRuntimeException");
        resolver.exceptionHandlerAdvices.add(advice1);
        resolver.exceptionHandlerAdvices.add(advice2);

        when(request.getRequestContext()).thenReturn(requestContext);

        ResponseStatusException rse = new ResponseStatusException(HttpStatus.NOT_FOUND);
        boolean handled = resolver.resolveException(request, response, null, rse);

        // 所有 handler 都跳过了 → 返回 false，交给下一个 ExceptionRegistry 解析器
        assertFalse(handled);
    }

    // ---- helper methods ----

    private static MappingHandlerMethod handlerOn(Object bean, String methodName) throws Exception {
        Class<?> clazz = bean.getClass();
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName)) {
                return new MappingHandlerMethod(bean, m);
            }
        }
        throw new NoSuchMethodException(clazz.getName() + "#" + methodName);
    }

    private ExceptionHandlerAdvice mockAdvice(String methodName) throws Exception {
        ExceptionHandlerAdvice advice = mock(ExceptionHandlerAdvice.class);
        RseExplicitHandler bean = new RseExplicitHandler();
        MappingHandlerMethod handlerMethod = handlerOn(bean, methodName);
        when(advice.resolveHandlerMethod(any())).thenReturn(handlerMethod);
        when(advice.isApplicableToBeanType(any())).thenReturn(true);
        return advice;
    }

    // ---- handler classes for isExplicitRseHandler tests ----

    static class DummyHandler {
        public void handleException(Throwable t) {}
        public void handleString(String s) {}
    }

    @SuppressWarnings("serial")
    static class SubResponseStatusException extends ResponseStatusException {
        SubResponseStatusException() { super(HttpStatus.NOT_FOUND); }
    }

    // 各种 @ExceptionHandler 声明方式
    static class RseExplicitHandler {
        @ExceptionHandler(ResponseStatusException.class)
        public void handleRse(ResponseStatusException e) {}

        @ExceptionHandler(SubResponseStatusException.class)
        public void handleSubRse(SubResponseStatusException e) {}

        @ExceptionHandler({ResponseStatusException.class, IllegalArgumentException.class})
        public void handleRseAndIae(ResponseStatusException e) {}

        @ExceptionHandler(Throwable.class)
        public void handleThrowable(Throwable e) {}

        @ExceptionHandler(RuntimeException.class)
        public void handleRuntimeException(RuntimeException e) {}

        @ExceptionHandler(Exception.class)
        public void handleException(Exception e) {}

        @ExceptionHandler(IllegalArgumentException.class)
        public void handleIae(IllegalArgumentException e) {}
    }
}