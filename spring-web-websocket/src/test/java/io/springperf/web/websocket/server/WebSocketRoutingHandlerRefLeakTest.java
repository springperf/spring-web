package io.springperf.web.websocket.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHandler;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * 回归 R3-A2：WS 升级被拒路径（Origin 校验失败）泄漏 FullHttpRequest。
 * <p>修复前 {@code sendForbiddenResponse} 只写 403 不释放请求引用，content ByteBuf
 * 直到连接关闭才释放；修复后拒绝路径立即 {@code ReferenceCountUtil.release}。
 * 无匹配路径 / 非升级请求应透传且不得提前释放（所有权仍属下游）。</p>
 */
class WebSocketRoutingHandlerRefLeakTest {

    private static WebSocketRoutingHandler handler() {
        return new WebSocketRoutingHandler(
                Map.of("/ws", mock(WebSocketHandler.class)),
                null, false, Collections.emptyList());
    }

    private static FullHttpRequest wsUpgradeRequest(String path) {
        // content 用非空字节：Unpooled.wrappedBuffer(空数组) 返回 EMPTY_BUFFER，
        // 其 release() 是 no-op、refCnt 恒 1，无法观测引用释放。
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path,
                Unpooled.wrappedBuffer(new byte[]{'x'}));
        req.headers().set(HttpHeaderNames.HOST, "localhost");
        req.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        req.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        req.headers().set(HttpHeaderNames.ORIGIN, "http://evil.example");
        return req;
    }

    @Test
    void forbiddenOrigin_rejectsAndReleasesRequest() {
        EmbeddedChannel channel = new EmbeddedChannel(handler());

        FullHttpRequest req = wsUpgradeRequest("/ws");
        channel.writeInbound(req);

        HttpResponse resp = channel.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.FORBIDDEN, resp.status());

        // 拒绝路径立即释放请求引用（修复前 refCnt 保持 1，content ByteBuf 泄漏）
        assertEquals(0, req.refCnt());
        // CLOSE listener 已执行：连接关闭
        assertFalse(channel.isOpen());
    }

    @Test
    void unmatchedPath_wsUpgrade_passesThroughWithoutRelease() {
        EmbeddedChannel channel = new EmbeddedChannel(handler());

        FullHttpRequest req = wsUpgradeRequest("/unmapped");
        channel.writeInbound(req);

        // 无匹配路径：原对象透传下游，不得提前释放（所有权转移给下游，由下游管理）
        FullHttpRequest received = channel.readInbound();
        assertSame(req, received);
        assertEquals(1, req.refCnt());
        received.release();
    }
}
