package io.springperf.web.core.invoker;

import io.springperf.web.annotation.Optimize;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvokableHandlerMethodTest {

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    // ==================== Constructor with bean + method ====================

    @Test
    void constructor_withoutOptimize_usesMethodHandleInvoker() throws Exception {
        Method method = PlainController.class.getMethod("hello", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new PlainController(), method);

        assertFalse(hm.isOptimize());
        assertTrue(hm.getInvoker() instanceof MethodHandleInvoker);
    }

    @Test
    void constructor_withOptimizeOnMethod_usesFastInvoker() throws Exception {
        Method method = MethodOptimizedController.class.getMethod("fastMethod", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new MethodOptimizedController(), method);

        assertTrue(hm.isOptimize());
        assertNotNull(hm.getInvoker());
        assertFalse(hm.getInvoker() instanceof MethodHandleInvoker);
    }

    @Test
    void constructor_withOptimizeOnClass_usesFastInvoker() throws Exception {
        Method method = ClassOptimizedController.class.getMethod("classOptimized", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new ClassOptimizedController(), method);

        assertTrue(hm.isOptimize());
        assertNotNull(hm.getInvoker());
        assertFalse(hm.getInvoker() instanceof MethodHandleInvoker);
    }

    @Test
    void constructor_withInvokerBean_usesBeanDirectly() throws Exception {
        DirectInvoker bean = new DirectInvoker();
        // Invoker has no business method; HandlerMethod needs a real Method for super()
        InvokableHandlerMethod hm = new InvokableHandlerMethod(bean, DirectInvoker.class.getMethod("invoke", Object[].class));

        assertFalse(hm.isOptimize());
        assertSame(bean, hm.getInvoker());
    }

    @Test
    void constructor_withHandlerMethodWithoutOptimize_usesMethodHandleInvoker() throws Exception {
        Method method = PlainController.class.getMethod("hello", String.class);
        InvokableHandlerMethod source = new InvokableHandlerMethod(new PlainController(), method);

        InvokableHandlerMethod hm = new InvokableHandlerMethod(source);
        assertFalse(hm.isOptimize());
        assertTrue(hm.getInvoker() instanceof MethodHandleInvoker);
    }

    @Test
    void constructor_withHandlerMethodWithOptimize_usesFastInvoker() throws Exception {
        Method method = MethodOptimizedController.class.getMethod("fastMethod", String.class);
        InvokableHandlerMethod source = new InvokableHandlerMethod(new MethodOptimizedController(), method);

        InvokableHandlerMethod hm = new InvokableHandlerMethod(source);
        assertTrue(hm.isOptimize());
        assertNotNull(hm.getInvoker());
        assertFalse(hm.getInvoker() instanceof MethodHandleInvoker);
    }

    @Test
    void constructor_fastInvokerFails_fallsBackToMethodHandleInvoker() throws Exception {
        Method method = MethodOptimizedController.class.getMethod("fastMethod", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new MethodOptimizedController(), method) {
            @Override
            protected Invoker createFastInvoker() {
                throw new RuntimeException("simulated failure");
            }
        };

        // optimized flag is true (annotation is present), but invoker fell back due to error
        assertTrue(hm.isOptimize());
        assertTrue(hm.getInvoker() instanceof MethodHandleInvoker);
    }

    // ==================== invoke and ResponseStatus ====================

    @Test
    void invoke_withoutResponseStatus_doesNotSetStatus() throws Throwable {
        Method method = PlainController.class.getMethod("hello", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new PlainController(), method);

        Object result = hm.invoke(new Object[]{"test"}, request, response);

        assertEquals("Hello test", result);
        verify(response, never()).setStatusCode(any(HttpStatus.class));
        verify(response, never()).sendError(any(HttpStatus.class), anyString());
    }

    @Test
    void invoke_withResponseStatus_setsStatusCode() throws Throwable {
        Method method = StatusController.class.getMethod("accepted");
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new StatusController(), method);

        Object result = hm.invoke(new Object[0], request, response);

        assertEquals("ok", result);
        verify(response).setStatusCode(HttpStatus.ACCEPTED);
    }

    @Test
    void invoke_withResponseStatusAndReason_sendsError() throws Throwable {
        Method method = StatusController.class.getMethod("badRequest");
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new StatusController(), method);

        Object result = hm.invoke(new Object[0], request, response);

        assertEquals("no", result);
        verify(response).sendError(HttpStatus.BAD_REQUEST, "bad");
    }

    @Test
    void setResponseStatus_nullResponse_doesNothing() throws Exception {
        Method method = StatusController.class.getMethod("accepted");
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new StatusController(), method);

        hm.setResponseStatus(request, null);

        verify(response, never()).setStatusCode(any(HttpStatus.class));
    }

    @Test
    void setResponseStatus_nullStatus_doesNothing() throws Exception {
        Method method = PlainController.class.getMethod("hello", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new PlainController(), method);

        hm.setResponseStatus(request, response);

        verify(response, never()).setStatusCode(any(HttpStatus.class));
    }

    // ==================== getMethodAndClassAnnotation ====================

    @Test
    void getMethodAndClassAnnotation_onMethod_findsAnnotation() throws Exception {
        Method method = MethodOptimizedController.class.getMethod("fastMethod", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new MethodOptimizedController(), method);

        Optimize ann = hm.getMethodAndClassAnnotation(Optimize.class);
        assertNotNull(ann);
    }

    @Test
    void getMethodAndClassAnnotation_onClass_findsAnnotation() throws Exception {
        Method method = ClassOptimizedController.class.getMethod("classOptimized", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new ClassOptimizedController(), method);

        Optimize ann = hm.getMethodAndClassAnnotation(Optimize.class);
        assertNotNull(ann);
    }

    @Test
    void getMethodAndClassAnnotation_notFound_returnsNull() throws Exception {
        Method method = PlainController.class.getMethod("hello", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new PlainController(), method);

        Optimize ann = hm.getMethodAndClassAnnotation(Optimize.class);
        assertNull(ann);
    }

    // ==================== createMethodParameters ====================

    @Test
    void createMethodParameters_returnsCorrectCount() throws Exception {
        Method method = MultiParamController.class.getMethod("multi", String.class, int.class, boolean.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new MultiParamController(), method);

        org.springframework.core.MethodParameter[] params = hm.createMethodParameters();

        assertEquals(3, params.length);
        assertEquals(String.class, params[0].getParameterType());
        assertEquals(int.class, params[1].getParameterType());
        assertEquals(boolean.class, params[2].getParameterType());
    }

    @Test
    void createMethodParameters_zeroParams_returnsEmpty() throws Exception {
        Method method = PlainController.class.getMethod("noop");
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new PlainController(), method);

        org.springframework.core.MethodParameter[] params = hm.createMethodParameters();

        assertEquals(0, params.length);
    }

    // ==================== isOptimized / getInvoker  ====================

    @Test
    void isOptimized_withoutOptimize_returnsFalse() throws Exception {
        Method method = PlainController.class.getMethod("hello", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new PlainController(), method);

        assertFalse(hm.isOptimize());
    }

    @Test
    void isOptimized_withOptimize_returnsTrue() throws Exception {
        Method method = MethodOptimizedController.class.getMethod("fastMethod", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new MethodOptimizedController(), method);

        assertTrue(hm.isOptimize());
    }

    @Test
    void getInvoker_returnsConfiguredInvoker() throws Exception {
        Method method = PlainController.class.getMethod("hello", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new PlainController(), method);

        assertNotNull(hm.getInvoker());
    }

    // ==================== integration: fast invoker correctness ====================

    @Test
    void invoke_onOptimizedMethod_returnsCorrectResult() throws Throwable {
        Method method = MethodOptimizedController.class.getMethod("fastMethod", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new MethodOptimizedController(), method);

        Object result = hm.invoke(new Object[]{"World"}, request, response);

        assertEquals("Fast World", result);
    }

    // ==================== helper classes ====================

    @SuppressWarnings("unused")
    static class PlainController {
        public String hello(String name) { return "Hello " + name; }
        public void noop() {}
    }

    @SuppressWarnings("unused")
    static class MethodOptimizedController {
        @Optimize
        public String fastMethod(String name) { return "Fast " + name; }
    }

    @SuppressWarnings("unused")
    @Optimize
    static class ClassOptimizedController {
        public String classOptimized(String name) { return "Class " + name; }
    }

    @SuppressWarnings("unused")
    static class StatusController {
        @ResponseStatus(HttpStatus.ACCEPTED)
        public String accepted() { return "ok"; }

        @ResponseStatus(value = HttpStatus.BAD_REQUEST, reason = "bad")
        public String badRequest() { return "no"; }
    }

    @SuppressWarnings("unused")
    static class MultiParamController {
        public String multi(String s, int i, boolean b) { return s + i + b; }
    }

    static class DirectInvoker implements Invoker {
        @Override
        public Object invoke(Object[] args) { return "direct-result"; }
    }
}