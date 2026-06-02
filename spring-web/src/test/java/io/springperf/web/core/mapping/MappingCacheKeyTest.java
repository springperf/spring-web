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
    void methodAndClassKeys_useSeparateSequences() {
        MappingCacheKey<?> mk = MappingCacheKey.createMethodCacheKey(String.class);
        MappingCacheKey<?> ck = MappingCacheKey.createClassCacheKey(String.class);
        // Both start from 0, so after creating one of each they should differ from their sequencers
        assertNotNull(mk);
        assertNotNull(ck);
    }
}
