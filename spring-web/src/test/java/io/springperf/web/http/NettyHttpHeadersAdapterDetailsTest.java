package io.springperf.web.http;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NettyHttpHeadersAdapterDetailsTest {

    // ==================== 鍙妯″紡 ====================

    @Test
    void readOnly_getFirst_size_isEmpty() {
        HttpHeaders netty = new DefaultHttpHeaders(false);
        netty.add("A", "1");
        NettyHttpHeadersAdapter adapter = new NettyHttpHeadersAdapter(netty);
        assertEquals("1", adapter.getFirst("A"));
        assertEquals(1, adapter.size());
        assertFalse(adapter.isEmpty());
    }

    @Test
    void readOnly_containsKey_containsValue() {
        HttpHeaders netty = new DefaultHttpHeaders(false);
        netty.add("A", "1");
        netty.add("B", "2");
        NettyHttpHeadersAdapter adapter = new NettyHttpHeadersAdapter(netty);
        assertTrue(adapter.containsKey("A"));
        assertTrue(adapter.containsKey("B"));
        assertFalse(adapter.containsKey(123));
        assertTrue(adapter.containsValue("1"));
        assertFalse(adapter.containsValue("nope"));
        assertFalse(adapter.containsValue(123));
    }

    @Test
    void readOnly_get_keySet_values_entrySet() {
        HttpHeaders netty = new DefaultHttpHeaders(false);
        netty.add("A", "1");
        netty.add("B", "2");
        netty.add("B", "3");
        NettyHttpHeadersAdapter adapter = new NettyHttpHeadersAdapter(netty);

        assertEquals(Collections.singletonList("1"), adapter.get("A"));
        assertNull(adapter.get(123));
        assertNull(adapter.get("missing"));

        Set<String> keys = adapter.keySet();
        assertTrue(keys.contains("A"));
        assertTrue(keys.contains("B"));

        Collection<List<String>> values = adapter.values();
        assertEquals(2, values.size());

        Map<String, String> single = adapter.toSingleValueMap();
        assertEquals("1", single.get("A"));
        assertEquals("2", single.get("B"));

        Set<Map.Entry<String, List<String>>> entries = adapter.entrySet();
        assertEquals(2, entries.size());
    }

    @Test
    void readOnly_writeOperations_throwUnsupported() {
        NettyHttpHeadersAdapter adapter = new NettyHttpHeadersAdapter(new DefaultHttpHeaders(false));
        assertThrows(UnsupportedOperationException.class, () -> adapter.add("A", "1"));
        assertThrows(UnsupportedOperationException.class, () -> adapter.addAll("A", Collections.singletonList("1")));
        assertThrows(UnsupportedOperationException.class, () -> adapter.addAll(new LinkedMultiValueMap<>()));
        assertThrows(UnsupportedOperationException.class, () -> adapter.set("A", "1"));
        assertThrows(UnsupportedOperationException.class, () -> adapter.setAll(Collections.emptyMap()));
        assertThrows(UnsupportedOperationException.class, () -> adapter.put("A", Collections.singletonList("1")));
        assertThrows(UnsupportedOperationException.class, () -> adapter.remove("A"));
        assertThrows(UnsupportedOperationException.class, () -> adapter.putAll(Collections.emptyMap()));
        assertThrows(UnsupportedOperationException.class, adapter::clear);
    }

    // ==================== 鍙啓妯″紡 ====================

    @Test
    void writable_add_addAll_set_delegateToNetty() {
        HttpHeaders netty = new DefaultHttpHeaders(false);
        NettyHttpHeadersAdapter adapter = new NettyHttpHeadersAdapter(netty, true);

        adapter.add("A", "1");
        adapter.addAll("B", Arrays.asList("2", "3"));
        adapter.set("C", "4");

        assertEquals(Collections.singletonList("1"), netty.getAll("A"));
        assertEquals(Arrays.asList("2", "3"), netty.getAll("B"));
        assertEquals(Collections.singletonList("4"), netty.getAll("C"));
    }

    @Test
    void writable_addAllMultiValueMap_setAll() {
        HttpHeaders netty = new DefaultHttpHeaders(false);
        NettyHttpHeadersAdapter adapter = new NettyHttpHeadersAdapter(netty, true);

        MultiValueMap<String, String> src = new LinkedMultiValueMap<>();
        src.add("X", "a");
        src.add("X", "b");
        adapter.addAll(src);
        assertEquals(Arrays.asList("a", "b"), netty.getAll("X"));

        adapter.setAll(Collections.singletonMap("Y", "c"));
        assertEquals(Collections.singletonList("c"), netty.getAll("Y"));
    }

    @Test
    void writable_put_returnsPrevious() {
        HttpHeaders netty = new DefaultHttpHeaders(false);
        netty.add("A", "old");
        NettyHttpHeadersAdapter adapter = new NettyHttpHeadersAdapter(netty, true);

        List<String> previous = adapter.put("A", Arrays.asList("new1", "new2"));
        assertEquals(Collections.singletonList("old"), previous);
        assertEquals(Arrays.asList("new1", "new2"), netty.getAll("A"));

        assertNull(adapter.put("B", Collections.singletonList("1")));
    }

    @Test
    void writable_remove_existingAndMissing() {
        HttpHeaders netty = new DefaultHttpHeaders(false);
        netty.add("A", "1");
        NettyHttpHeadersAdapter adapter = new NettyHttpHeadersAdapter(netty, true);

        assertEquals(Collections.singletonList("1"), adapter.remove("A"));
        assertNull(netty.get("A"));
        assertNull(adapter.remove("A"));
        assertNull(adapter.remove(123));
    }

    @Test
    void writable_putAll_clear() {
        HttpHeaders netty = new DefaultHttpHeaders(false);
        NettyHttpHeadersAdapter adapter = new NettyHttpHeadersAdapter(netty, true);

        Map<String, List<String>> map = new java.util.HashMap<>();
        map.put("A", Collections.singletonList("1"));
        map.put("B", Collections.singletonList("2"));
        adapter.putAll(map);
        assertEquals("1", netty.get("A"));
        assertEquals("2", netty.get("B"));

        adapter.clear();
        assertTrue(netty.isEmpty());
    }

    // ==================== WebHttpHeaders ====================

    @Test
    void webHttpHeaders_getContentType_cachedAndResetOnSet() {
        HttpHeaders netty = new DefaultHttpHeaders(false);
        netty.set("Content-Type", "application/json");
        WebHttpHeaders view = new WebHttpHeaders(new NettyHttpHeadersAdapter(netty, true));
        assertSame(view.getContentType(), view.getContentType());

        view.setContentType(null);
        assertNull(view.getContentType(), "setContentType(null) 鍚庣紦瀛樺簲澶辨晥骞惰繑鍥?null");

        netty.set("Content-Type", "text/plain");
        assertNotNull(view.getContentType());
    }

    @Test
    void webHttpHeaders_empty_getContentTypeNull() {
        WebHttpHeaders view = new WebHttpHeaders();
        assertNull(view.getContentType());
    }

    @Test
    void webHttpHeaders_mapOperations_delegate() {
        WebHttpHeaders view = new WebHttpHeaders();
        view.add("A", "1");
        view.add("A", "2");
        assertEquals("1", view.getFirst("A"));
        assertEquals(Arrays.asList("1", "2"), view.get("A"));
        assertTrue(view.containsKey("A"));
        assertEquals(1, view.size());
        assertFalse(view.isEmpty());

        MultiValueMap<String, String> other = new LinkedMultiValueMap<>();
        other.add("B", "3");
        view.addAll(other);
        assertEquals("3", view.getFirst("B"));

        Map<String, String> single = view.toSingleValueMap();
        assertEquals("1", single.get("A"));

        view.set("C", "4");
        assertEquals("4", view.getFirst("C"));

        view.setAll(Collections.singletonMap("D", "5"));
        assertEquals("5", view.getFirst("D"));

        List<String> removed = view.remove("A");
        assertEquals(Arrays.asList("1", "2"), removed);
        assertNull(view.get("A"));
    }

    @Test
    void webHttpHeaders_put_putAll_clear_values_entrySet() {
        WebHttpHeaders view = new WebHttpHeaders();
        view.put("A", Collections.singletonList("1"));
        view.put("B", Arrays.asList("2", "3"));
        assertEquals(Collections.singletonList("1"), view.get("A"));
        assertEquals(Arrays.asList("2", "3"), view.get("B"));
        assertFalse(view.containsValue("3"), "澶氬€间綔涓虹嫭绔嬪€煎瓨鍌紝containsValue 涓嶅尮閰嶅崟椤?);
        assertFalse(view.containsValue("nope"));

        assertEquals(2, view.entrySet().size());
        assertEquals(2, view.values().size());

        view.putAll(Collections.singletonMap("C", Collections.singletonList("4")));
        assertEquals("4", view.getFirst("C"));

        view.clear();
        assertTrue(view.isEmpty());
    }
}
