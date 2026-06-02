package io.springperf.web.core.exception;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExceptionHandlerAdviceTest {

    @Mock
    ControllerAdviceBean adviceBean;

    @Mock
    ExceptionHandlerMethodResolver resolver;

    static class TestController {
        public String handleException(Exception ex) {
            return "handled";
        }
    }

    @Test
    void constructor_withObject() {
        TestController bean = new TestController();
        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(bean, resolver);

        assertSame(resolver, advice.resolver);
        assertSame(bean, advice.realBean);
        assertNull(advice.adviceBean);
    }

    @Test
    void constructor_withAdviceBean() {
        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(adviceBean, resolver);

        assertSame(adviceBean, advice.adviceBean);
        assertSame(resolver, advice.resolver);
        assertNull(advice.realBean);
    }

    @Test
    void isApplicableToBeanType_withObject_matching() {
        TestController bean = new TestController();
        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(bean, resolver);

        assertTrue(advice.isApplicableToBeanType(TestController.class));
    }

    @Test
    void isApplicableToBeanType_withObject_notMatching() {
        TestController bean = new TestController();
        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(bean, resolver);

        assertFalse(advice.isApplicableToBeanType(String.class));
    }

    @Test
    void isApplicableToBeanType_withAdviceBean_applicable() {
        when(adviceBean.isApplicableToBeanType(String.class)).thenReturn(true);
        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(adviceBean, resolver);

        assertTrue(advice.isApplicableToBeanType(String.class));
    }

    @Test
    void isApplicableToBeanType_withAdviceBean_notApplicable() {
        when(adviceBean.isApplicableToBeanType(String.class)).thenReturn(false);
        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(adviceBean, resolver);

        assertFalse(advice.isApplicableToBeanType(String.class));
    }

    @Test
    void isApplicableToBeanType_withObject_nullBeanType_returnsFalse() {
        TestController bean = new TestController();
        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(bean, resolver);

        assertFalse(advice.isApplicableToBeanType(null));
    }

    @Test
    void resolveHandlerMethod_found() throws Exception {
        TestController bean = new TestController();
        Method method = TestController.class.getMethod("handleException", Exception.class);
        when(resolver.resolveMethodByThrowable(any(RuntimeException.class))).thenReturn(method);

        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(bean, resolver);
        MappingHandlerMethod result = advice.resolveHandlerMethod(new RuntimeException());

        assertNotNull(result);
        assertSame(method, result.getMethod());
    }

    @Test
    void resolveHandlerMethod_cached() throws Exception {
        TestController bean = new TestController();
        Method method = TestController.class.getMethod("handleException", Exception.class);
        when(resolver.resolveMethodByThrowable(any(RuntimeException.class))).thenReturn(method);

        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(bean, resolver);
        MappingHandlerMethod first = advice.resolveHandlerMethod(new RuntimeException());
        MappingHandlerMethod second = advice.resolveHandlerMethod(new RuntimeException());

        assertNotNull(first);
        assertSame(first, second);
        // resolver.resolveMethodByThrowable is called before cache check (on every call)
        verify(resolver, times(2)).resolveMethodByThrowable(any());
    }

    @Test
    void resolveHandlerMethod_notFound() {
        when(resolver.resolveMethodByThrowable(any(RuntimeException.class))).thenReturn(null);

        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(new TestController(), resolver);
        assertNull(advice.resolveHandlerMethod(new RuntimeException()));
    }

    @Test
    void getRealBean_alreadySet() {
        TestController bean = new TestController();
        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(bean, resolver);

        assertSame(bean, advice.getRealBean());
        // verify adviceBean.resolveBean() is never called
        verify(adviceBean, never()).resolveBean();
    }

    @Test
    void getRealBean_lazyResolve() {
        TestController bean = new TestController();
        when(adviceBean.resolveBean()).thenReturn(bean);

        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(adviceBean, resolver);
        Object result = advice.getRealBean();

        assertSame(bean, result);
        verify(adviceBean).resolveBean();
    }

    @Test
    void getOrder_withAdviceBean() {
        when(adviceBean.getOrder()).thenReturn(100);

        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(adviceBean, resolver);

        assertEquals(100, advice.getOrder());
    }

    @Test
    void getOrder_withObject() {
        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice(new TestController(), resolver);

        assertEquals(Integer.MIN_VALUE, advice.getOrder());
    }
}