package io.springperf.web.websocket.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHandler;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * 回归 P2 并发组 #11：WS 升级成功后必须移除 HTTP 层的 ReadTimeoutHandler。
 * <p>Http2ChannelInitializer 在 readTimeout>0 时为每个连接添加 ReadTimeoutHandler；
 * 修复前 {@code removeHttpHandlers} 不处理它，空闲 WebSocket 连接会被
 * {@code ReadTimeoutException} 误杀。</p>
 */
class WebSocketRoutingHandlerUpgradeRemovalTest {

    @Test
    void successfulUpgrade_removesReadTimeoutHandler() {
        EmbeddedChannel channel = new EmbeddedChannel();
        // 模拟 HTTP 层配置：readTimeout>0 时 Http2ChannelInitializer 添加的 ReadTimeoutHandler
        channel.pipeline().addLast(new ReadTimeoutHandler(1000, TimeUnit.MILLISECONDS));
        channel.pipeline().addLast(new WebSocketRoutingHandler(
                Map.of("/ws", mock(WebSocketHandler.class)), null, false, null));

        FullHttpRequest req = upgradeRequest("/ws");
        channel.writeInbound(req);
        channel.runPendingTasks();

        assertNull(channel.pipeline().get(ReadTimeoutHandler.class),
                "升级成功后 HTTP 层 ReadTimeoutHandler 必须被移除，否则空闲 WS 被误杀");
        channel.finishAndReleaseAll();
    }

    private static FullHttpRequest upgradeRequest(String path) {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path,
                Unpooled.wrappedBuffer(new byte[0]));
        req.headers().set(HttpHeaderNames.HOST, "localhost");
        req.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        req.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        return req;
    }
}
