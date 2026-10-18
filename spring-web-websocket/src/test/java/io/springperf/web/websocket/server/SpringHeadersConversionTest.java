package io.springperf.web.websocket.server;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 握手头转换：{@link WebSocketHandshakeHandler#toSpringHeaders}。
 * <p>
 * 重点是 Spring {@code HttpHeaders} 的契约，而不是「能不能存进去」：业务查握手头普遍用小写
 * （{@code getHandshakeHeaders().getFirst("sec-websocket-protocol")}），且它是可写的 {@code MultiValueMap}。 曾经的适配器继承
 * {@code HttpHeaders} 却自带 {@code LinkedHashMap}，这两个契约都破了。
 * </p>
 */
class SpringHeadersConversionTest {

    private static io.netty.handler.codec.http.HttpHeaders nettyHeaders() {
        io.netty.handler.codec.http.HttpHeaders netty = new DefaultHttpHeaders(false);
        netty.add("Sec-WebSocket-Protocol", "chat");
        netty.add("X-Multi", "a");
        netty.add("X-Multi", "b");
        netty.set("Content-Type", "application/json");
        return netty;
    }

    @Test
    void lookupIsCaseInsensitive() {
        HttpHeaders headers = WebSocketHandshakeHandler.toSpringHeaders(nettyHeaders());

        // Spring 的官方示例写法就是小写
        assertEquals("chat", headers.getFirst("sec-websocket-protocol"));
        assertEquals("chat", headers.getFirst("SEC-WEBSOCKET-PROTOCOL"));
        assertEquals("application/json", headers.getFirst("content-type"));
        assertEquals("application/json", headers.getFirst("Content-Type"));
    }

    @Test
    void multiValueHeadersKeepOrderAndValues() {
        HttpHeaders headers = WebSocketHandshakeHandler.toSpringHeaders(nettyHeaders());

        assertEquals(Arrays.asList("a", "b"), headers.get("X-Multi"));
        assertEquals(Arrays.asList("a", "b"), headers.get("x-multi"));
        assertEquals(3, headers.size());
    }

    @Test
    void returnedHeadersRemainWritable() {
        HttpHeaders headers = WebSocketHandshakeHandler.toSpringHeaders(nettyHeaders());

        // 转换结果必须是自洽的 MultiValueMap：写进去的必须能被读出来（旧适配器的 add 写父类私有 Map，静默丢失）
        headers.add("X-New", "v");
        assertTrue(headers.containsKey("X-New"));
        assertEquals("v", headers.getFirst("x-new"));

        headers.set("X-Multi", "only");
        assertEquals(List.of("only"), headers.get("x-multi"));
    }

    @Test
    void emptyHeadersConvertToEmptyHttpHeaders() {
        HttpHeaders headers = WebSocketHandshakeHandler.toSpringHeaders(new DefaultHttpHeaders(false));

        assertTrue(headers.isEmpty());
        assertNull(headers.getFirst("anything"));
    }
}
