package io.springperf.web.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Object2LongOpenHashMapTest {

    @Test
    void putAndGet_roundTrip() {
        Object2LongOpenHashMap map = new Object2LongOpenHashMap();
        assertEquals(0L, map.put("a", 1L));
        assertEquals(1L, map.get("a"));
        assertTrue(map.containsKey("a"));
        assertEquals(1, map.size());
        assertFalse(map.isEmpty());
    }

    @Test
    void putExisting_returnsOldValue() {
        Object2LongOpenHashMap map = new Object2LongOpenHashMap();
        map.put("a", 1L);
        assertEquals(1L, map.put("a", 2L));
        assertEquals(2L, map.get("a"));
        assertEquals(1, map.size(), "覆盖不应增加 size");
    }

    @Test
    void getMissing_returnsDefaultZero() {
        Object2LongOpenHashMap map = new Object2LongOpenHashMap();
        assertEquals(0L, map.get("missing"));
        assertFalse(map.containsKey("missing"));
        assertEquals(0, map.size());
        assertTrue(map.isEmpty());
    }

    @Test
    void nullKey_putThrows() {
        Object2LongOpenHashMap map = new Object2LongOpenHashMap();
        assertThrows(IllegalArgumentException.class, () -> map.put(null, 1L));
    }

    @Test
    void nullKey_getAndContainsKey_safe() {
        Object2LongOpenHashMap map = new Object2LongOpenHashMap();
        assertEquals(0L, map.get(null));
        assertFalse(map.containsKey(null));
    }

    @Test
    void invalidLoadFactor_throws() {
        assertThrows(IllegalArgumentException.class, () -> new Object2LongOpenHashMap(16, 0f));
        assertThrows(IllegalArgumentException.class, () -> new Object2LongOpenHashMap(16, 1.5f));
    }

    @Test
    void putMany_triggersRehash_preservesAllEntries() {
        Object2LongOpenHashMap map = new Object2LongOpenHashMap(4);
        int n = 1000;
        for (int i = 0; i < n; i++) {
            map.put("key-" + i, i);
        }
        assertEquals(n, map.size());
        for (int i = 0; i < n; i++) {
            assertEquals(i, map.get("key-" + i), "扩容后 key-" + i + " 应保留");
            assertTrue(map.containsKey("key-" + i));
        }
    }

    @Test
    void updateAfterRehash_works() {
        Object2LongOpenHashMap map = new Object2LongOpenHashMap(4);
        for (int i = 0; i < 100; i++) {
            map.put("k" + i, i);
        }
        assertEquals(50L, map.put("k50", -1L));
        assertEquals(-1L, map.get("k50"));
    }

    @Test
    void hashCollision_linearProbing_correct() {
        // 构造大量 key，即便哈希冲突也通过线性探测正确存取
        Object2LongOpenHashMap map = new Object2LongOpenHashMap(8);
        String[] keys = {"Aa", "BB", "C1", "D2", "E3", "F4", "G5", "H6", "I7", "J8"};
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], i);
        }
        for (int i = 0; i < keys.length; i++) {
            assertEquals(i, map.get(keys[i]), keys[i] + " 应命中");
        }
        assertEquals(keys.length, map.size());
    }

    @Test
    void defaultCapacity_works() {
        Object2LongOpenHashMap map = new Object2LongOpenHashMap();
        map.put("x", 42L);
        assertEquals(42L, map.get("x"));
    }

    @Test
    void randomOperations_consistent() {
        Object2LongOpenHashMap map = new Object2LongOpenHashMap(16);
        Random random = new Random(42);
        Set<String> inserted = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String key = "r" + random.nextInt(500);
            long value = random.nextLong();
            map.put(key, value);
            inserted.add(key);
        }
        // 与重新遍历对比：所有插入的 key 都应可读回非零值（值可能为 0，但 key 必须存在）
        for (String key : inserted) {
            assertTrue(map.containsKey(key), key + " 应存在");
        }
        assertEquals(inserted.size(), map.size(), "去重后的 key 数应等于 map size");
    }
}
