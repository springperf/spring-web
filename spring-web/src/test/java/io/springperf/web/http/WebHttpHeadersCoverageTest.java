package io.springperf.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * {@link WebHttpHeaders} 的 MultiValueMap 表面与 Content-Type 快路径。
 * <p>
 * 早期版本还有若干用 {@code Unsafe} 改写静态字段、在 Spring 6/7 两种模式间切换的用例
 * （{@code HEADERS_IS_MULTI_VALUE_MAP} / {@code AS_MULTI_VALUE_MAP}）——随本分支专用
 * Spring 7、那套运行时分支被移除，这些机制性用例一并删除。
 */
class WebHttpHeadersCoverageTest {

    @Test
    void addAll_twoArg_appendsMultipleValues() {
        WebHttpHeaders headers = new WebHttpHeaders();
        headers.addAll("X-Foo", Arrays.asList("a", "b"));
        assertEquals("a", headers.getFirst("X-Foo"));
        assertEquals(2, headers.get("X-Foo").size());
    }

    @Test
    void constructor_withProvidedMap_holdsReference() {
        MultiValueMap<String, String> store = new LinkedMultiValueMap<>();
        WebHttpHeaders headers = new WebHttpHeaders(store);
        store.set("X-K", "v");
        assertEquals("v", headers.getFirst("X-K"));
    }

    /** 覆盖 MultiValueMap 全表面（Spring 7 下父类缺的那几个方法走 asMultiValueMap 视图）。 */
    @Test
    void supportsFullMultiValueMapSurface() {
        WebHttpHeaders h = new WebHttpHeaders();
        assertTrue(h.isEmpty());
        h.add("h1", "v1");
        assertEquals("v1", h.getFirst("h1"));
        h.addAll("h2", Arrays.asList("a", "b"));
        assertEquals(Arrays.asList("a", "b"), h.get("h2"));
        h.set("h3", "v3");
        assertEquals("v3", h.getFirst("h3"));
        MultiValueMap<String, String> other = new LinkedMultiValueMap<>();
        other.add("h4", "v4");
        h.addAll(other);
        assertEquals("v4", h.getFirst("h4"));
        h.setAll(Collections.singletonMap("h5", "v5"));
        assertEquals(5, h.size());
        assertTrue(h.containsKey("h5"));
        assertTrue(h.containsValue(Arrays.asList("a", "b")));
        Map<String, String> single = h.toSingleValueMap();
        assertEquals("v1", single.get("h1"));
        h.put("h6", Collections.singletonList("p1"));
        assertEquals(Collections.singletonList("p1"), h.get("h6"));
        h.putAll(Collections.singletonMap("h7", Collections.singletonList("p7")));
        assertTrue(h.keySet().containsAll(Arrays.asList("h1", "h7")));
        assertTrue(h.values().stream().anyMatch(v -> v.contains("p7")));
        assertTrue(h.entrySet().stream().anyMatch(e -> "h7".equals(e.getKey())));
        assertEquals(Collections.singletonList("p1"), h.remove("h6"));
        h.clear();
        assertTrue(h.isEmpty());
        assertNull(h.getFirst("h1"));
    }

    /** delegateMap 与父类存储是同一个视图：写入必须双向可见。 */
    @Test
    void writesThroughToSharedSuperStorage() {
        MultiValueMap<String, String> store = new LinkedMultiValueMap<>();
        WebHttpHeaders h = new WebHttpHeaders(store);
        h.add("Accept", "application/json");
        assertEquals("application/json", store.getFirst("Accept"));
        assertEquals("application/json", h.getFirst("Accept"));
    }

    /**
     * Content-Type 快路径（可写 Netty 视图）：读写必须直接落到 Netty headers 且语义与 Spring
     * {@code HttpHeaders.setContentType/getContentType} 一致（含 null 等价 remove）。
     */
    @Test
    void contentTypeFastPath_writableNettyView_writesAndReadsThroughNetty() {
        io.netty.handler.codec.http.HttpHeaders netty = new io.netty.handler.codec.http.DefaultHttpHeaders(false);
        WebHttpHeaders h = new WebHttpHeaders(new NettyHttpHeadersAdapter(netty, true));

        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertEquals("application/json", netty.get("Content-Type"), "应直接写入 Netty headers");
        assertEquals(org.springframework.http.MediaType.APPLICATION_JSON, h.getContentType());
        // 不缓存：等值即可，不要求同一实例（见 WebHttpHeaders 类注释的权衡说明）
        assertEquals(h.getContentType(), h.getContentType());

        org.springframework.http.MediaType textual = org.springframework.http.MediaType
                .parseMediaType("text/plain;charset=UTF-8");
        h.setContentType(textual);
        assertEquals(textual, h.getContentType(), "set 后应读到新值");
        assertEquals("text/plain;charset=UTF-8", netty.get("Content-Type"));

        h.setContentType(null);
        assertNull(netty.get("Content-Type"), "null 等价 remove（对齐 Spring 语义）");
        assertNull(h.getContentType());
    }

    /** 只读 Netty 视图（请求侧）：写操作仍必须抛 UnsupportedOperationException，不得被快路径绕过。 */
    @Test
    void contentTypeFastPath_readOnlyView_stillRejectsWrite() {
        io.netty.handler.codec.http.HttpHeaders netty = new io.netty.handler.codec.http.DefaultHttpHeaders(false);
        netty.set("Content-Type", "application/json");
        WebHttpHeaders ro = new WebHttpHeaders(new NettyHttpHeadersAdapter(netty, false));

        assertEquals(org.springframework.http.MediaType.APPLICATION_JSON, ro.getContentType());
        assertThrows(UnsupportedOperationException.class,
                () -> ro.setContentType(org.springframework.http.MediaType.TEXT_PLAIN));
    }

    /** 无 Netty 视图时走父类语义（控制组）。 */
    @Test
    void contentTypeWithNoNettyView_usesSuperSemantics() {
        WebHttpHeaders h = new WebHttpHeaders();
        assertNull(h.getContentType());
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertEquals(org.springframework.http.MediaType.APPLICATION_JSON, h.getContentType());
        assertNotNull(h.get("Content-Type"));
    }
}
