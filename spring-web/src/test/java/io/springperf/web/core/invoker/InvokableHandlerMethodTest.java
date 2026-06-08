package io.springperf.web.core.invoker;

import io.springperf.web.annotation.Optimize;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    // ==================== createMethodParameters with CGLIB proxy ====================

    @Test
    void createMethodParameters_withCglibProxy_resolvesToClassMethod() throws Exception {
        // 通过 HandlerMethodParameter 从接口合并参数注解
        Object proxy = createCglibProxy(new ApiImpl());
        Method proxyMethod = proxy.getClass().getMethod("save", String.class, String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(proxy, proxyMethod);

        MethodParameter[] params = hm.createMethodParameters();

        assertEquals(2, params.length);
        assertEquals(1, params[0].getParameterAnnotations().length);
        assertTrue(params[0].hasParameterAnnotation(RequestBody.class));
        assertEquals(1, params[1].getParameterAnnotations().length);
        assertTrue(params[1].hasParameterAnnotation(RequestParam.class));
    }

    @Test
    void createMethodParameters_withCglibProxy_preservesMetadata() throws Exception {
        Object proxy = createCglibProxy(new ApiImpl());
        Method proxyMethod = proxy.getClass().getMethod("save", String.class, String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(proxy, proxyMethod);

        MethodParameter[] params = hm.createMethodParameters();

        assertEquals(2, params.length);
        assertEquals(String.class, params[0].getParameterType());
        assertEquals(String.class, params[1].getParameterType());
    }

    @Test
    void createMethodParameters_withCglibProxy_noAnnotations_returnsMethodAsIs() throws Exception {
        Object proxy = createCglibProxy(new PlainController());
        Method proxyMethod = proxy.getClass().getMethod("hello", String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(proxy, proxyMethod);

        MethodParameter[] params = hm.createMethodParameters();

        assertEquals(1, params.length);
        assertEquals(0, params[0].getParameterAnnotations().length);
    }

    @Test
    void createMethodParameters_withCglibProxy_zeroParams_returnsEmpty() throws Exception {
        Object proxy = createCglibProxy(new NoOpController());
        Method proxyMethod = proxy.getClass().getMethod("noop");
        InvokableHandlerMethod hm = new InvokableHandlerMethod(proxy, proxyMethod);

        MethodParameter[] params = hm.createMethodParameters();

        assertEquals(0, params.length);
    }

    @Test
    void createMethodParameters_withMultiLevelInterface_resolvesToClassMethod() throws Exception {
        // 通过 HandlerMethodParameter 从多级接口层级查找注解
        Object proxy = createCglibProxy(new DeepApiImpl());
        Method proxyQueryMethod = proxy.getClass().getMethod("query", String.class, String.class, String.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(proxy, proxyQueryMethod);

        MethodParameter[] params = hm.createMethodParameters();

        assertEquals(3, params.length);
        assertEquals(String.class, params[0].getParameterType());
        assertEquals(String.class, params[1].getParameterType());
        assertEquals(String.class, params[2].getParameterType());
        // 多级接口 DeepApi → ExtendedApi → AnnotatedApi，注解应从继承链合并
        assertEquals(0, params[0].getParameterAnnotations().length);
        assertEquals(0, params[1].getParameterAnnotations().length);
        assertEquals(1, params[2].getParameterAnnotations().length);
        assertTrue(params[2].hasParameterAnnotation(RequestParam.class));
    }

    @Test
    void createMethodParameters_withDirectClass_stillWorks() throws Exception {
        // 非代理场景回归测试
        Method method = MultiParamController.class.getMethod("multi", String.class, int.class, boolean.class);
        InvokableHandlerMethod hm = new InvokableHandlerMethod(new MultiParamController(), method);

        MethodParameter[] params = hm.createMethodParameters();

        assertEquals(3, params.length);
        assertEquals(String.class, params[0].getParameterType());
        assertEquals(int.class, params[1].getParameterType());
        assertEquals(boolean.class, params[2].getParameterType());
    }

    private static Object createCglibProxy(Object target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        return factory.getProxy();
    }

    // ==================== helper classes for proxy tests ====================

    interface AnnotatedApi {
        String save(@RequestBody String body, @RequestParam("id") String id);
    }

    @SuppressWarnings("unused")
    static class ApiImpl implements AnnotatedApi {
        @Override
        public String save(String body, String id) { return body + id; }
    }

    interface PathApi {
        String get(@PathVariable("name") String name);
    }

    @SuppressWarnings("unused")
    static class PathApiImpl implements PathApi {
        @Override
        public String get(String name) { return name; }
    }

    // 三层接口继承：DeepApi → ExtendedApi → AnnotatedApi
    interface ExtendedApi extends AnnotatedApi {
        String find(@PathVariable("name") String name);
    }

    interface DeepApi extends ExtendedApi {
        String query(String key, String value, @RequestParam("q") String q);
    }

    @SuppressWarnings("unused")
    static class DeepApiImpl implements DeepApi {
        @Override public String save(String body, String id) { return body + id; }
        @Override public String find(String name) { return name; }
        @Override public String query(String key, String value, String q) { return q; }
    }

    @SuppressWarnings("unused")
    static class NoOpController {
        public void noop() {}
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