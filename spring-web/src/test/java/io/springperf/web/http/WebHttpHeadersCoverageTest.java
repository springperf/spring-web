package io.springperf.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * {@link WebHttpHeaders} 的功能测试：构造语义（引用持有）与 Content-Type 快路径。
 */
class WebHttpHeadersCoverageTest {

    @Test
    void addAll_twoArg_appendsMultipleValues() {
        WebHttpHeaders headers = new WebHttpHeaders();
        headers.addAll("X-Foo", java.util.Arrays.asList("a", "b"));
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
}
