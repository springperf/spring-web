package io.springperf.web.core.mapping;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

class MappingHandlerMethodTest {

    static class TestController {
        @RequestMapping("/test")
        public String hello() {
            return "hello";
        }
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

    @Test
    void clearCache_removesSlotValue_sharedAcrossInstances() throws NoSuchMethodException {
        MappingCacheKey<String> key = MappingCacheKey.createMethodCacheKey(String.class);
        handlerMethod.set(key, "testValue");
        assertEquals("testValue", handlerMethod.get(key));

        // 另一个实例共享同一方法的缓存数组
        TestController bean = new TestController();
        Method method = TestController.class.getMethod("hello");
        MappingHandlerMethod other = new MappingHandlerMethod(bean, method);

        MappingHandlerMethod.clearCache(key);

        assertNull(handlerMethod.get(key));
        assertNull(other.get(key), "共享数组的槽位应被同时清空");
    }

    @Test
    void clearCache_classCache_removesSlotValue() {
        MappingCacheKey<String> key = MappingCacheKey.createClassCacheKey(String.class);
        handlerMethod.set(key, "classValue");
        assertEquals("classValue", handlerMethod.get(key));

        MappingHandlerMethod.clearCache(key);

        assertNull(handlerMethod.get(key));
    }

    @Test
    void clearAllCaches_freshInstanceSeesNoCache() throws NoSuchMethodException {
        MappingCacheKey<String> key = MappingCacheKey.createMethodCacheKey(String.class);
        handlerMethod.set(key, "m");
        assertEquals("m", handlerMethod.get(key));

        MappingHandlerMethod.clearAllCaches();

        // 销毁场景：旧实例随上下文废弃，新实例应看不到任何缓存
        TestController bean = new TestController();
        Method method = TestController.class.getMethod("hello");
        MappingHandlerMethod fresh = new MappingHandlerMethod(bean, method);
        assertNull(fresh.get(key));
    }

    // ========== M4：扩容后存活实例数组发散导致 clearCache 失效 ==========

    @Test
    void clearCache_invalidatesAcrossDivergentInstances() throws NoSuchMethodException {
        // 复现 M4：扩容后某些存活实例仍引用旧(已脱离静态表)数组，clearCache 仅置空静态表当前数组，
        // 发散实例的缓存槽未被失效，Javadoc 声称的"运行期失效"为假。
        MappingCacheKey<String> keyLow = MappingCacheKey.createMethodCacheKey(String.class);
        MappingCacheKey<String> keyHigh = MappingCacheKey.createMethodCacheKey(String.class);

        TestController bean = new TestController();
        Method method = TestController.class.getMethod("hello");
        MappingHandlerMethod m1 = new MappingHandlerMethod(bean, method);
        MappingHandlerMethod m2 = new MappingHandlerMethod(bean, method); // 同 userMethod，共享静态缓存

        // 1. m2 先访问 -> 静态表创建初始数组(长度=keyLow.index+1)，m2.methodCache 指向它
        m2.get(keyLow);
        // 2. m1 在更高 index 写入 -> set 触发扩容，生成新数组并写回静态表；m2.methodCache 仍指向旧数组
        m1.set(keyHigh, "highValue");
        // 3. m2 在 keyLow 写入 -> 修复前写入 m2 仍持有的旧(已脱离静态表)数组
        m2.set(keyLow, "lowValue");

        // 4. clearCache 仅置空静态表当前数组，旧数组槽位应一并失效
        MappingHandlerMethod.clearCache(keyLow);

        // 修复前：m2 仍引用旧数组，get(keyLow) 返回 "lowValue"（未被失效）；
        // 修复后：getCache 始终从静态表取最新数组，m2 看到 null。
        assertNull(m1.get(keyLow), "m1 的槽位应被 clearCache 清空");
        assertNull(m2.get(keyLow), "m2 不应再引用已脱离静态表的旧数组，槽位应被 clearCache 清空");
    }
}
