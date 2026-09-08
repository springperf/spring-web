package io.springperf.web.websocket.server;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link SpringHeadersAdapter}：Netty headers → Spring HttpHeaders 的 Map 适配。
 */
class SpringHeadersAdapterTest {

    private io.netty.handler.codec.http.HttpHeaders nettyHeaders() {
        io.netty.handler.codec.http.HttpHeaders netty = new DefaultHttpHeaders(false);
        netty.add("Content-Type", "application/json");
        netty.add("X-Multi", "a");
        netty.add("X-Multi", "b");
        return netty;
    }

    @Test
    void constructor_copiesNettyHeadersIntoMap() {
        SpringHeadersAdapter adapter = new SpringHeadersAdapter(nettyHeaders());
        assertEquals(2, adapter.size());
        assertEquals("application/json", adapter.getFirst("Content-Type"));
        assertEquals(Arrays.asList("a", "b"), adapter.get("X-Multi"));
    }

    @Test
    void getFirst_missingHeader_returnsNull() {
        SpringHeadersAdapter adapter = new SpringHeadersAdapter(nettyHeaders());
        assertNull(adapter.getFirst("Missing"));
    }

    @Test
    void keySet_and_entrySet_and_containsKey() {
        SpringHeadersAdapter adapter = new SpringHeadersAdapter(nettyHeaders());
        Set<String> keys = adapter.keySet();
        assertEquals(2, keys.size());
        assertTrue(keys.contains("Content-Type"));
        assertTrue(adapter.containsKey("X-Multi"));
        assertFalse(adapter.containsKey("Nope"));
        assertEquals(2, adapter.entrySet().size());
    }

    @Test
    void isEmpty_and_values() {
        SpringHeadersAdapter adapter = new SpringHeadersAdapter(nettyHeaders());
        assertFalse(adapter.isEmpty());
        assertTrue(adapter.containsValue(Arrays.asList("a", "b")));
        assertEquals(2, adapter.values().size());
        SpringHeadersAdapter empty = new SpringHeadersAdapter(new DefaultHttpHeaders(false));
        assertTrue(empty.isEmpty());
    }

    @Test
    void put_remove_clear_areMutable() {
        SpringHeadersAdapter adapter = new SpringHeadersAdapter(nettyHeaders());
        adapter.put("X-New", Arrays.asList("1", "2"));
        assertEquals(Arrays.asList("1", "2"), adapter.get("X-New"));

        List<String> removed = adapter.remove("Content-Type");
        assertEquals(Arrays.asList("application/json"), removed);
        assertNull(adapter.get("Content-Type"));

        adapter.clear();
        assertTrue(adapter.isEmpty());
    }
}