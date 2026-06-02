package io.springperf.web.context;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import static org.junit.jupiter.api.Assertions.*;

class WebComponentWrapperTest {

    static class PlainBean {
        private final String value;

        PlainBean(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "PlainBean:" + value;
        }
    }

    @Order(1)
    static class OrderedBean {
    }

    @Test
    void constructor_setsComponentNameFromClass() {
        WebComponentWrapper<PlainBean> wrapper = new WebComponentWrapper<>(new PlainBean("test"));
        assertNotNull(wrapper.getComponent());
        assertEquals("PlainBean", wrapper.getComponentName());
    }

    @Test
    void constructor_withCustomName_usesCustomName() {
        WebComponentWrapper<PlainBean> wrapper = new WebComponentWrapper<>(new PlainBean("test"), "customName");
        assertEquals("customName", wrapper.getComponentName());
    }

    @Test
    void setComponentName_overridesName() {
        WebComponentWrapper<PlainBean> wrapper = new WebComponentWrapper<>(new PlainBean("test"), "original");
        wrapper.setComponentName("updated");
        assertEquals("updated", wrapper.getComponentName());
    }

    @Test
    void getOrder_withoutAnnotation_returnsDefaultPriority() {
        WebComponentWrapper<PlainBean> wrapper = new WebComponentWrapper<>(new PlainBean("test"));
        assertEquals(Ordered.LOWEST_PRECEDENCE - 10000, wrapper.getOrder());
    }

    @Test
    void getOrder_withAnnotation_returnsAnnotationValue() {
        WebComponentWrapper<OrderedBean> wrapper = new WebComponentWrapper<>(new OrderedBean());
        assertEquals(1, wrapper.getOrder());
    }

    @Test
    void getOrder_cachesResult() {
        WebComponentWrapper<PlainBean> wrapper = new WebComponentWrapper<>(new PlainBean("test"));
        int first = wrapper.getOrder();
        int second = wrapper.getOrder();
        assertEquals(first, second);
    }

    @Test
    void setOrder_overridesAnnotation() {
        WebComponentWrapper<OrderedBean> wrapper = new WebComponentWrapper<>(new OrderedBean());
        wrapper.setOrder(999);
        assertEquals(999, wrapper.getOrder());
    }

    @Test
    void setOrder_cached_thenSetOverrides() {
        WebComponentWrapper<PlainBean> wrapper = new WebComponentWrapper<>(new PlainBean("test"));
        int before = wrapper.getOrder();
        wrapper.setOrder(777);
        assertEquals(777, wrapper.getOrder());
    }

    @Test
    void toString_containsWrapperNameAndComponent() {
        WebComponentWrapper<PlainBean> wrapper = new WebComponentWrapper<>(new PlainBean("value42"), "myBean");
        String str = wrapper.toString();
        assertTrue(str.contains("Wrapper:"));
        assertTrue(str.contains("myBean"));
        assertTrue(str.contains("PlainBean:value42"));
    }

    @Test
    void getComponentClass_returnsWrappedClass() {
        WebComponentWrapper<PlainBean> wrapper = new WebComponentWrapper<>(new PlainBean("test"));
        assertEquals(PlainBean.class, wrapper.getComponentClass());
    }
}