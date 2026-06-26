package io.springperf.web.core.codec.interceptor;

import io.springperf.web.context.WebComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.method.ControllerAdviceBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebComponentControllerAdviceBeanTest {

    @Mock
    ControllerAdviceBean adviceBean;

    @Test
    void constructor_withControllerAdviceBean() {
        Object realBean = new Object();
        when(adviceBean.resolveBean()).thenReturn(realBean);
        when(adviceBean.toString()).thenReturn("testAdvice");

        WebComponentControllerAdviceBean<Object> wrapper = new WebComponentControllerAdviceBean<>(adviceBean);

        assertSame(realBean, wrapper.getComponent());
        assertEquals("testAdvice", wrapper.getComponentName());
    }

    @Test
    void constructor_withControllerAdviceBeanAndRealBean() {
        Object realBean = new Object();
        when(adviceBean.toString()).thenReturn("testAdvice");

        WebComponentControllerAdviceBean<Object> wrapper = new WebComponentControllerAdviceBean<>(adviceBean, realBean);

        assertSame(realBean, wrapper.getComponent());
        assertEquals("testAdvice", wrapper.getComponentName());
    }

    @Test
    void constructor_withComponentName() {
        Object realBean = new Object();

        WebComponentControllerAdviceBean<Object> wrapper = new WebComponentControllerAdviceBean<>("myComponent", realBean);

        assertSame(realBean, wrapper.getComponent());
        assertEquals("myComponent", wrapper.getComponentName());
    }

    @Test
    void constructor_withoutAdviceBean_implementsWebComponent() {
        WebComponentControllerAdviceBean<Object> wrapper = new WebComponentControllerAdviceBean<>("test", new Object());

        assertInstanceOf(WebComponent.class, wrapper);
    }

    @Test
    void isApplicableToBeanType_withoutAdviceBean_returnsTrue() {
        WebComponentControllerAdviceBean<Object> wrapper = new WebComponentControllerAdviceBean<>("test", new Object());

        assertTrue(wrapper.isApplicableToBeanType(String.class));
    }

    @Test
    void isApplicableToBeanType_withAdviceBean_returnsTrue() {
        when(adviceBean.isApplicableToBeanType(String.class)).thenReturn(true);
        Object realBean = new Object();
        when(adviceBean.resolveBean()).thenReturn(realBean);
        when(adviceBean.toString()).thenReturn("testAdvice");

        WebComponentControllerAdviceBean<Object> wrapper = new WebComponentControllerAdviceBean<>(adviceBean);

        assertTrue(wrapper.isApplicableToBeanType(String.class));
    }

    @Test
    void isApplicableToBeanType_withAdviceBean_returnsFalse() {
        when(adviceBean.isApplicableToBeanType(Integer.class)).thenReturn(false);
        Object realBean = new Object();
        when(adviceBean.resolveBean()).thenReturn(realBean);
        when(adviceBean.toString()).thenReturn("testAdvice");

        WebComponentControllerAdviceBean<Object> wrapper = new WebComponentControllerAdviceBean<>(adviceBean);

        assertFalse(wrapper.isApplicableToBeanType(Integer.class));
    }
}