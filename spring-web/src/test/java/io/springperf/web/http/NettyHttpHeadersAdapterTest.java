package io.springperf.web.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link NettyHttpHeadersAdapter} 零拷贝只读视图接入
 * {@link NettyServerHttpRequest#getHeaders()} 后的行为。
 */
@ExtendWith(MockitoExtension.class)
class NettyHttpHeadersAdapterTest {

    @Mock
    private WebContext webContext;
    @Mock
    private ChannelHandlerContext ctx;

    private FullHttpRequest nativeRequest;
    private NettyServerHttpRequest req;

    @BeforeEach
    void setUp() {
        nativeRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/test", Unpooled.buffer());
        nativeRequest.headers().add("Content-Type", "application/json");
        nativeRequest.headers().add("Accept", "application/json");
        nativeRequest.headers().add("Content-Length", "0");
        req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
    }

    @Test
    void getHeaders_isZeroCopyLiveView() {
        WebHttpHeaders headers = (WebHttpHeaders) req.getHeaders();
        // 零拷贝：native Netty 请求头后续新增，视图立即可见（非拷贝快照）
        nativeRequest.headers().add("X-Zero-Copy", "probe");
        assertEquals("probe", headers.getFirst("x-zero-copy"));
        assertEquals("probe", headers.getFirst("X-Zero-Copy"));
    }

    @Test
    void getFirst_isCaseInsensitive() {
        WebHttpHeaders headers = (WebHttpHeaders) req.getHeaders();
        assertEquals("application/json", headers.getFirst("Accept")); // 规范大小写
        assertEquals("application/json", headers.getFirst("accept")); // 小写：旧拷贝 miss，适配器命中
    }

    @Test
    void getContentType_and_getAccept_parse() {
        WebHttpHeaders headers = (WebHttpHeaders) req.getHeaders();
        assertEquals(MediaType.APPLICATION_JSON, headers.getContentType());
        assertEquals(MediaType.APPLICATION_JSON, headers.getAccept().get(0));
    }

    @Test
    void getContentType_cachedWithinSameInstance() {
        WebHttpHeaders headers = (WebHttpHeaders) req.getHeaders();
        assertSame(headers.getContentType(), headers.getContentType());
    }

    @Test
    void writes_throughToNativeRequest() {
        WebHttpHeaders headers = (WebHttpHeaders) req.getHeaders();
        headers.set("X-Custom", "set-value");
        assertEquals("set-value", nativeRequest.headers().get("X-Custom"));

        headers.add("X-Multi", "a");
        headers.add("X-Multi", "b");
        assertEquals(Arrays.asList("a", "b"), nativeRequest.headers().getAll("X-Multi"));

        // 反向：直接写 Netty 请求头，视图可见（同一存储）
        nativeRequest.headers().set("Y", "z");
        assertEquals("z", headers.getFirst("Y"));
    }

    @Test
    void mapReadMethods_work() {
        WebHttpHeaders headers = (WebHttpHeaders) req.getHeaders();
        assertFalse(headers.isEmpty());
        assertTrue(headers.containsKey("Content-Type"));
        assertTrue(headers.containsKey("content-type"));
        assertEquals(3, headers.size());
        assertTrue(headers.keySet().contains("Accept"));
        assertNotNull(headers.get("Content-Type"));
    }

    // ==================== writable 模式（响应侧） ====================

    @Test
    void writableMode_writesThroughToNetty() {
        HttpHeaders nettyHeaders = new DefaultHttpHeaders(false);
        WebHttpHeaders view = new WebHttpHeaders(new NettyHttpHeadersAdapter(nettyHeaders, true));

        view.set("Content-Type", "application/json");
        view.add("X-Multi", "a");
        view.add("X-Multi", "b");

        assertEquals("application/json", nettyHeaders.get("Content-Type"));
        assertEquals(Arrays.asList("a", "b"), nettyHeaders.getAll("X-Multi"));

        // 反向：直接写 Netty，视图可见（同一存储）
        nettyHeaders.set("Y", "z");
        assertEquals("z", view.getFirst("Y"));
        nettyHeaders.add("Y", "w");
        assertEquals(Arrays.asList("z", "w"), view.get("Y"));
    }

    @Test
    void writableMode_removeAndClear_delegateToNetty() {
        HttpHeaders nettyHeaders = new DefaultHttpHeaders(false);
        WebHttpHeaders view = new WebHttpHeaders(new NettyHttpHeadersAdapter(nettyHeaders, true));

        view.set("A", "1");
        view.set("B", "2");
        assertEquals(Collections.singletonList("1"), view.remove("A"));
        assertNull(nettyHeaders.get("A"));
        assertNull(view.get("A"));

        view.clear();
        assertTrue(nettyHeaders.isEmpty());
        assertTrue(view.isEmpty());
    }
}
