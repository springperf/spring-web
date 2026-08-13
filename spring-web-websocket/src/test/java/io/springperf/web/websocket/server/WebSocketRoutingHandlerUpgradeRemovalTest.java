package io.springperf.web.websocket.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHandler;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // 模拟 HTTP 层配置：readTimeout>0 时 Http2ChannelInitializer 添加的 ReadTimeoutHandler。
        // 必须补齐 HttpServerCodec + HttpObjectAggregator（真实 pipeline 由
        // WebSocketAutoConfiguration 经 addAfterAggregator 注入 routing handler）——
        // WebSocketServerHandshaker.handshake 会检查 pipeline 中存在 HttpDecoder/
        // HttpServerCodec，缺失时抛 IllegalStateException 导致握手失败、channel 关闭，
        // 断言会因 pipeline 整体销毁而假通过（修复前即此假阳性）。
        channel.pipeline().addLast(new HttpServerCodec());
        channel.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
        channel.pipeline().addLast(new ReadTimeoutHandler(1000, TimeUnit.MILLISECONDS));
        channel.pipeline().addLast(new WebSocketRoutingHandler(
                Map.of("/ws", mock(WebSocketHandler.class)), null, false, null));

        FullHttpRequest req = upgradeRequest("/ws");
        // 直接 fireChannelRead 推入 pipeline：不经过 writeInbound 的 inbound 缓存，
        // 避免 finishAndReleaseAll 对已释放请求双重释放（req 原始引用由本测试管理）。
        channel.pipeline().fireChannelRead(req);
        channel.runPendingTasks();

        assertNull(channel.pipeline().get(ReadTimeoutHandler.class),
                "升级成功后 HTTP 层 ReadTimeoutHandler 必须被移除，否则空闲 WS 被误杀");

        // 关键回归断言：握手成功后请求原始引用必须归零。netty handshake 内部 retain 的
        // 引用由 WebSocketServerHandshaker$2 消费，此处验证调用方持有的原始引用已被释放。
        // 修复前不释放，每次 WS 握手泄漏 1 个 FullHttpRequest 引用（refCnt 停留在 1）。
        assertEquals(0, req.refCnt(),
                "握手成功后请求必须被释放，否则每次 WS 握手泄漏一个 FullHttpRequest 引用");

        // finishAndReleaseAll 只清理 outbound（握手响应）与 inbound 缓冲；req 不在其中，
        // 不会双重释放。
        channel.finishAndReleaseAll();
    }

    private static FullHttpRequest upgradeRequest(String path) {
        // 用 Unpooled.buffer(0)：可管理 refCnt 的真实缓冲。wrappedBuffer(new byte[0])
        // 返回 EMPTY_BUFFER（release 是 no-op、refCnt 恒 0），无法用于泄漏回归断言。
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path,
                Unpooled.buffer(0));
        req.headers().set(HttpHeaderNames.HOST, "localhost");
        req.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        req.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        return req;
    }
}
