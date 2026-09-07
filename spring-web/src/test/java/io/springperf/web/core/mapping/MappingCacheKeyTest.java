package io.springperf.web.core.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MappingCacheKeyTest {

    @Test
    void createMethodCacheKey_hasCorrectDefaults() {
        MappingCacheKey<String> key = MappingCacheKey.createMethodCacheKey(String.class);
        assertFalse(key.classCache);
        assertEquals(String.class, key.type);
    }

    @Test
    void createClassCacheKey_hasCorrectDefaults() {
        MappingCacheKey<Integer> key = MappingCacheKey.createClassCacheKey(Integer.class);
        assertTrue(key.classCache);
        assertEquals(Integer.class, key.type);
    }

    @Test
    void multipleMethodKeys_haveDistinctIndices() {
        MappingCacheKey<?> k1 = MappingCacheKey.createMethodCacheKey(String.class);
        MappingCacheKey<?> k2 = MappingCacheKey.createMethodCacheKey(String.class);
        assertNotEquals(k1.index, k2.index);
    }

    @Test
    void multipleClassKeys_haveDistinctIndices() {
        MappingCacheKey<?> k1 = MappingCacheKey.createClassCacheKey(String.class);
        MappingCacheKey<?> k2 = MappingCacheKey.createClassCacheKey(String.class);
        assertNotEquals(k1.index, k2.index);
    }

    @Test
    void methodAndClassKeys_useSeparateSequences() throws Exception {
        // 两个序列由独立 AtomicInteger 驱动：创建 method 键不推进 class 计数，反之亦然
        long methodBefore = counter("METHOD_CACHE_SEQ");
        long classBefore = counter("CLASS_CACHE_SEQ");

        MappingCacheKey<?> mk = MappingCacheKey.createMethodCacheKey(String.class);
        long methodAfterMethodKey = counter("METHOD_CACHE_SEQ");
        long classAfterMethodKey = counter("CLASS_CACHE_SEQ");

        MappingCacheKey<?> ck = MappingCacheKey.createClassCacheKey(String.class);
        long classAfterClassKey = counter("CLASS_CACHE_SEQ");
        long methodAfterClassKey = counter("METHOD_CACHE_SEQ");

        // method 键只推进 method 序列
        assertEquals(methodBefore + 1, methodAfterMethodKey);
        assertEquals(classBefore, classAfterMethodKey);
        // class 键只推进 class 序列
        assertEquals(classBefore + 1, classAfterClassKey);
        assertEquals(methodAfterMethodKey, methodAfterClassKey);
        // 两个键可并存（index 来自各自序列）
        assertNotNull(mk);
        assertNotNull(ck);
    }

    private static long counter(String fieldName) throws Exception {
        java.lang.reflect.Field field = MappingCacheKey.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicInteger) field.get(null)).longValue();
    }
}
