package io.springperf.web.core.exception;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link ExceptionHandlerExceptionResolver#resolveInjectedException}：
 * 当 {@code @ExceptionHandler} 方法沿 cause 链选中某个特定异常时，注入与 handler
 * 异常参数类型兼容的 cause 链上最具体的异常，而非根异常（避免反射 ClassCastException）。
 */
class ExceptionHandlerExceptionResolverInjectionTest {

    private final ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();

    static class HandlerBean {
        @SuppressWarnings("unused")
        public void handleIae(IllegalArgumentException e) {}

        @SuppressWarnings("unused")
        public void handleRuntime(RuntimeException e) {}

        @SuppressWarnings("unused")
        public void handleThrowable(Throwable e) {}

        @SuppressWarnings("unused")
        public void noThrowableParam(String s) {}
    }

    private MappingHandlerMethod hm(String methodName, Class<?> paramType) throws Exception {
        HandlerMethod springHm = new HandlerMethod(new HandlerBean(),
                HandlerBean.class.getMethod(methodName, paramType));
        return new MappingHandlerMethod(springHm);
    }

    @Test
    void handlerParamIaE_selectsCompatibleCause() throws Exception {
        // 根：IllegalStateException（非 IllegalArgumentException）；cause：NumberFormatException（是 IllegalArgumentException）
        NumberFormatException nfe = new NumberFormatException("bad");
        IllegalStateException root = new IllegalStateException("wrap", nfe);

        MappingHandlerMethod handler = hm("handleIae", IllegalArgumentException.class);
        Throwable injected = resolver.resolveInjectedException(handler, root);
        assertSame(nfe, injected, "应注入 cause 链上类型兼容 IllegalArgumentException 的那个异常");
    }

    @Test
    void handlerParamRuntime_rootCompatible_returnsRoot() throws Exception {
        NumberFormatException nfe = new NumberFormatException("bad");
        IllegalStateException root = new IllegalStateException("wrap", nfe);

        MappingHandlerMethod handler = hm("handleRuntime", RuntimeException.class);
        assertSame(root, resolver.resolveInjectedException(handler, root),
                "根异常本身是 RuntimeException，应直接命中");
    }

    @Test
    void handlerParamThrowable_returnsRoot() throws Exception {
        NumberFormatException nfe = new NumberFormatException("bad");
        IllegalStateException root = new IllegalStateException("wrap", nfe);

        MappingHandlerMethod handler = hm("handleThrowable", Throwable.class);
        assertSame(root, resolver.resolveInjectedException(handler, root));
    }

    @Test
    void handlerWithoutThrowableParam_returnsRoot() throws Exception {
        NumberFormatException nfe = new NumberFormatException("bad");
        IllegalStateException root = new IllegalStateException("wrap", nfe);

        MappingHandlerMethod handler = hm("noThrowableParam", String.class);
        assertSame(root, resolver.resolveInjectedException(handler, root));
    }

    @Test
    void noCompatibleCause_fallsBackToRoot() throws Exception {
        Exception root = new Exception("plain");
        MappingHandlerMethod handler = hm("handleIae", IllegalArgumentException.class);
        assertSame(root, resolver.resolveInjectedException(handler, root),
                "cause 链无兼容类型时回退根异常");
    }
}