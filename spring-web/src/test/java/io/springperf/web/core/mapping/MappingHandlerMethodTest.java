package io.springperf.web.core.mapping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class MappingHandlerMethodTest {

    static class TestController {
        @RequestMapping("/test")
        public String hello() { return "hello"; }
    }

    private MappingHandlerMethod handlerMethod;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        TestController bean = new TestController();
        Method method = TestController.class.getMethod("hello");
        handlerMethod = new MappingHandlerMethod(bean, method);
    }

    @Test
    void get_withoutSet_returnsNull() {
        MappingCacheKey<String> key = MappingCacheKey.createMethodCacheKey(String.class);
        assertNull(handlerMethod.get(key));
    }

    @Test
    void setAndGet_methodCache_returnsValue() {
        MappingCacheKey<String> key = MappingCacheKey.createMethodCacheKey(String.class);
        handlerMethod.set(key, "testValue");
        assertEquals("testValue", handlerMethod.get(key));
    }

    @Test
    void setAndGet_classCache_returnsValue() {
        MappingCacheKey<String> key = MappingCacheKey.createClassCacheKey(String.class);
        handlerMethod.set(key, "classValue");
        assertEquals("classValue", handlerMethod.get(key));
    }

    @Test
    void get_indexOutOfBounds_returnsNull() {
        // Use a key with large index
        MappingCacheKey<String> key = MappingCacheKey.createMethodCacheKey(String.class);
        // Don't set, index will be >= cache length
        assertNull(handlerMethod.get(key));
    }

    @Test
    void set_largeIndex_expandsCache() {
        MappingCacheKey<String> k1 = MappingCacheKey.createMethodCacheKey(String.class);
        MappingCacheKey<String> k2 = MappingCacheKey.createMethodCacheKey(String.class);
        // k2 has larger index, will trigger expansion
        handlerMethod.set(k1, "value1");
        handlerMethod.set(k2, "value2");
        assertEquals("value1", handlerMethod.get(k1));
        assertEquals("value2", handlerMethod.get(k2));
    }

    @Test
    void getUserClass_returnsUserClass() {
        assertNotNull(handlerMethod.getUserClass());
        assertEquals(TestController.class, handlerMethod.getUserClass());
    }

    @Test
    void construct_withHandlerMethod_worksCorrectly() throws NoSuchMethodException {
        TestController bean = new TestController();
        Method method = TestController.class.getMethod("hello");
        HandlerMethod hm = new HandlerMethod(bean, method);
        MappingHandlerMethod fromHandlerMethod = new MappingHandlerMethod(hm);

        assertNotNull(fromHandlerMethod.getUserClass());
        assertEquals(TestController.class, fromHandlerMethod.getUserClass());
    }

    @Test
    void methodAndClassCaches_areIndependent() {
        MappingCacheKey<String> methodKey = MappingCacheKey.createMethodCacheKey(String.class);
        MappingCacheKey<String> classKey = MappingCacheKey.createClassCacheKey(String.class);

        handlerMethod.set(methodKey, "methodValue");
        handlerMethod.set(classKey, "classValue");

        assertEquals("methodValue", handlerMethod.get(methodKey));
        assertEquals("classValue", handlerMethod.get(classKey));
    }

    @Test
    void multipleValuesInMethodCache() {
        MappingCacheKey<String> k1 = MappingCacheKey.createMethodCacheKey(String.class);
        MappingCacheKey<Integer> k2 = MappingCacheKey.createMethodCacheKey(Integer.class);

        handlerMethod.set(k1, "str");
        handlerMethod.set(k2, 42);

        assertEquals("str", handlerMethod.get(k1));
        assertEquals(42, handlerMethod.get(k2));
    }

    @Test
    void overrideValue_updatesExistingEntry() {
        MappingCacheKey<String> key = MappingCacheKey.createMethodCacheKey(String.class);
        handlerMethod.set(key, "original");
        handlerMethod.set(key, "updated");
        assertEquals("updated", handlerMethod.get(key));
    }

    @Test
    void diffKeyOrder_cachesResizeCorrectly() {
        // Create keys with different indices
        MappingCacheKey<String> k1 = MappingCacheKey.createMethodCacheKey(String.class);
        MappingCacheKey<String> k2 = MappingCacheKey.createMethodCacheKey(String.class);
        MappingCacheKey<String> k3 = MappingCacheKey.createMethodCacheKey(String.class);

        // Set in non-sequential order
        handlerMethod.set(k3, "third");
        handlerMethod.set(k1, "first");
        handlerMethod.set(k2, "second");

        assertEquals("first", handlerMethod.get(k1));
        assertEquals("second", handlerMethod.get(k2));
        assertEquals("third", handlerMethod.get(k3));
    }
}