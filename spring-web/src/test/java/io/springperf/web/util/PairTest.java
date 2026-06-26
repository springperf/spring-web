package io.springperf.web.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PairTest {

    @Test
    void of_createsPairWithGivenValues() {
        Pair<String, Integer> pair = Pair.of("key", 42);
        assertEquals("key", pair.getFirst());
        assertEquals(42, pair.getSecond());
    }

    @Test
    void of_createsPairWithNullKey() {
        Pair<String, Integer> pair = Pair.of(null, 1);
        assertNull(pair.getFirst());
        assertEquals(1, pair.getSecond());
    }

    @Test
    void of_createsPairWithNullValue() {
        Pair<String, Integer> pair = Pair.of("a", null);
        assertEquals("a", pair.getFirst());
        assertNull(pair.getSecond());
    }

    @Test
    void of_withNulls() {
        Pair<String, String> pair = Pair.of(null, null);
        assertNull(pair.getFirst());
        assertNull(pair.getSecond());
    }
}
