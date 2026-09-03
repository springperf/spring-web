package io.springperf.web.core.exception;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.ArgumentResolverRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补充 ExceptionHandlerExceptionResolver 覆盖率：@ControllerAdvice 扫描、
 * 缓存 advice 路径、返回值解析、sendError 失败兜底与 handler 不适用跳过。
 */
@ExtendWith(MockitoExtension.class)
class ExceptionHandlerExceptionResolverDetailsTest {

    public static class CustomException extends RuntimeException {
        public CustomException(String message) { super(message); }
    }

    @ControllerAdvice
    public static class MyAdvice {
        @ExceptionHandler(RuntimeException.class)
        public String handleRuntime(RuntimeException ex) {
            return "handled";
        }
    }

    @ControllerAdvice
    public static class VoidAdvice {
        @ExceptionHandler(RuntimeException.class)
        public void handle(RuntimeException ex) {}
    }

    @ControllerAdvice
    public static class NoMappingAdvice {
        public void unrelated() {}
    }

    public static class SampleController {
        @SuppressWarnings("unused")
        public void doNothing() {}
    }

    public static class CachedController {
        @SuppressWarnings("unused")
        public void target() {}

        @ExceptionHandler(RuntimeException.class)
        public String handle(RuntimeException ex) { return "cached"; }
    }

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void closeCtx() {
        if (ctx != null) {
            ctx.close();
        }
    }

    private ExceptionHandlerExceptionResolver resolver(Class<?>... adviceClasses) throws Exception {
        ctx = new AnnotationConfigApplicationContext();
        for (Class<?> adviceClass : adviceClasses) {
            ctx.registerBean(adviceClass);
        }
        ctx.refresh();

        ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();
        WebContext webContext = mock(WebContext.class);
        when(webContext.getCtx()).thenReturn(ctx);
        when(webContext.getWebComponentWithDefault(eq(ArgumentResolverRegistry.class), any(ArgumentResolverRegistry.class)))
                .thenReturn(new ArgumentResolverRegistry());
        when(webContext.getWebComponentWithDefault(eq(ReturnValueResolverRegistry.class), any(ReturnValueResolverRegistry.class)))
                .thenReturn(mock(ReturnValueResolverRegistry.class));
        resolver.initWithWebContext(webContext);
        resolver.initComponentPhase2();
        return resolver;
    }

    private WebServerHttpRequest requestWithContext() {
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        requestContext = new MapRequestContext();
        when(request.getRequestContext()).thenReturn(requestContext);
        return request;
    }

    private RequestContext requestContext;

    private static class MapRequestContext implements RequestContext {
        final Map<String, Object> attrs = new HashMap<>();
        final Map<RequestAttribute<?>, Object> typed = new HashMap<>();

        @Override public Map<String, Object> getAttributes() { return attrs; }
        @Override public Object getAttribute(String name) { return attrs.get(name); }
        @Override public void setAttribute(String name, Object o) { attrs.put(name, o); }
        @Override public Object removeAttribute(String name) { return attrs.remove(name); }
        @Override public <T> T getAttribute(RequestAttribute<T> key) { return (T) typed.get(key); }
        @Override public <T> void setAttribute(RequestAttribute<T> key, T value) { typed.put(key, value); }
    }

    /* ==================== @ControllerAdvice 扫描 ==================== */

    @Test
    void initWithWebContext_scansAndPhase2_registersAdvice() throws Exception {
        ExceptionHandlerExceptionResolver resolver = resolver(MyAdvice.class);

        assertEquals(1, resolver.exceptionHandlerAdvices.size());
        assertNotNull(resolver.argumentResolverRegistry);
        assertNotNull(resolver.returnValueResolverRegistry);
    }

    @Test
    void initWithWebContext_adviceWithoutExceptionMappings_notRegistered() throws Exception {
        ExceptionHandlerExceptionResolver resolver = resolver(NoMappingAdvice.class);

        assertTrue(resolver.exceptionHandlerAdvices.isEmpty());
    }

    /* ==================== resolveException 全流程 ==================== */

    @Test
    void resolveException_globalAdviceHandlesException_invokesHandler() throws Exception {
        ExceptionHandlerExceptionResolver resolver = resolver(MyAdvice.class);
        WebServerHttpRequest request = requestWithContext();
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        HandlerMethod handler = new HandlerMethod(new SampleController(),
                SampleController.class.getMethod("doNothing"));
        boolean handled = resolver.resolveException(request, response, handler, new RuntimeException("boom"));

        assertTrue(handled);
        // 返回值 "handled" 应进入 ReturnValueResolverRegistry
        verify(resolver.returnValueResolverRegistry)
                .resolveReturnValue(eq("handled"), any(), eq(request), eq(response));
    }

    @Test
    void resolveException_noAdvice_returnsFalse() throws Exception {
        ExceptionHandlerExceptionResolver resolver = resolver();
        WebServerHttpRequest request = requestWithContext();
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        HandlerMethod handler = new HandlerMethod(new SampleController(),
                SampleController.class.getMethod("doNothing"));
        boolean handled = resolver.resolveException(request, response, handler, new RuntimeException("boom"));

        assertFalse(handled);
    }

    @Test
    void resolveException_cachedControllerLocalHandler_handlesAndCaches() throws Exception {
        ExceptionHandlerExceptionResolver resolver = resolver();
        WebServerHttpRequest request = requestWithContext();
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        // 控制器自身带 @ExceptionHandler → 走缓存路径 getCachedExceptionHandlerAdvices
        HandlerMethod handler = new HandlerMethod(new CachedController(),
                CachedController.class.getMethod("target"));
        MappingResult.set(request, MappingResult.matched(pathMappingContext(handler)));

        boolean handled = resolver.resolveException(request, response, handler, new RuntimeException("cache"));

        assertTrue(handled);
        verify(resolver.returnValueResolverRegistry)
                .resolveReturnValue(eq("cached"), any(), eq(request), eq(response));
    }

    @Test
    void resolveException_cachedPath_responseStatusNotHandled_returnsFalse() throws Exception {
        ExceptionHandlerExceptionResolver resolver = resolver();
        WebServerHttpRequest request = requestWithContext();
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        HandlerMethod handler = new HandlerMethod(new CachedController(),
                CachedController.class.getMethod("target"));
        MappingResult.set(request, MappingResult.matched(pathMappingContext(handler)));

        // cached handler 只声明 RuntimeException；ResponseStatusException 不能走宽泛父类 → false
        boolean handled = resolver.resolveException(request, response, handler,
                new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertFalse(handled);
    }

    private static PathMappingContext pathMappingContext(HandlerMethod hm) {
        return new PathMappingContext(hm, Collections.emptyList(), "/target");
    }

    /* ==================== invokeAndWriteError 兜底 ==================== */

    @Test
    void invokeAndWriteError_sendErrorFails_isSwallowed() throws Exception {
        ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();
        ArgumentResolverRegistry throwingRegistry = mock(ArgumentResolverRegistry.class);
        when(throwingRegistry.resolveArguments(any(), any(), any()))
                .thenThrow(new RuntimeException("resolve failed"));
        resolver.argumentResolverRegistry = throwingRegistry;
        resolver.returnValueResolverRegistry = mock(ReturnValueResolverRegistry.class);

        WebServerHttpRequest request = requestWithContext();
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        doThrow(new RuntimeException("send error failed"))
                .when(response).sendError(eq(HttpStatus.INTERNAL_SERVER_ERROR), any(String.class));

        MappingHandlerMethod handlerMethod = new MappingHandlerMethod(
                new CachedController(), CachedController.class.getMethod("handle", RuntimeException.class));

        assertDoesNotThrow(() -> resolver.invokeAndWriteError(handlerMethod,
                new RuntimeException("boom"), request, response));
    }

    /* ==================== handler 返回 void + 已处理 ==================== */

    @Test
    void resolveException_voidHandlerSetsHandled_noReturnValueResolved() throws Exception {
        ExceptionHandlerExceptionResolver resolver = resolver(VoidAdvice.class);
        WebServerHttpRequest request = requestWithContext();
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        when(response.isHandled()).thenReturn(true);

        HandlerMethod handler = new HandlerMethod(new SampleController(),
                SampleController.class.getMethod("doNothing"));
        boolean handled = resolver.resolveException(request, response, handler, new RuntimeException("boom"));

        assertTrue(handled);
        verify(resolver.returnValueResolverRegistry, never()).resolveReturnValue(any(), any(), any(), any());
    }

    /* ==================== 无适用 advice 时跳过 ==================== */

    @ControllerAdvice(assignableTypes = String.class)
    public static class ScopedAdvice {
        @ExceptionHandler(RuntimeException.class)
        public String handle(RuntimeException ex) { return "scoped"; }
    }

    @Test
    void resolveException_adviceNotApplicableToHandlerType_skips() throws Exception {
        ExceptionHandlerExceptionResolver resolver = resolver(ScopedAdvice.class);
        WebServerHttpRequest request = requestWithContext();
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        // ScopedAdvice 仅对 String 类型的控制器适用，SampleController 不在范围内 → 跳过
        HandlerMethod handler = new HandlerMethod(new SampleController(),
                SampleController.class.getMethod("doNothing"));
        boolean handled = resolver.resolveException(request, response, handler, new RuntimeException("boom"));

        assertFalse(handled);
    }
}