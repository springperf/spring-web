package io.springperf.web.websocket.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHandler;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 回归 P2 并发组 #11：WS 升级成功后必须移除 HTTP 层的 ReadTimeoutHandler。
 * <p>
 * Http2ChannelInitializer 在 readTimeout>0 时为每个连接添加 ReadTimeoutHandler； 修复前 {@code removeHttpHandlers} 不处理它，空闲 WebSocket
 * 连接会被 {@code ReadTimeoutException} 误杀。
 * </p>
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
        channel.pipeline()
                .addLast(new WebSocketRoutingHandler(Map.of("/ws", mock(WebSocketHandler.class)), null, false, null));

        FullHttpRequest req = upgradeRequest("/ws");
        // 直接 fireChannelRead 推入 pipeline：不经过 writeInbound 的 inbound 缓存，
        // 避免 finishAndReleaseAll 对已释放请求双重释放（req 原始引用由本测试管理）。
        channel.pipeline().fireChannelRead(req);
        channel.runPendingTasks();

        String response = readOutboundText(channel);
        assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols"),
                "必须先确认握手真的成功（101），否则「移除」断言可能在失败路径上假通过，实际响应：" + response);
        assertTrue(response.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="), "101 响应必须带正确的 Sec-WebSocket-Accept");
        assertNotNull(WebSocketRoutingHandler.sessionOf(channel), "握手成功后 channel 上必须挂会话");
        assertNull(channel.pipeline().get(ReadTimeoutHandler.class),
                "升级成功后 HTTP 层 ReadTimeoutHandler 必须被移除，否则空闲 WS 被误杀");

        // 关键回归断言：握手成功后请求原始引用必须归零。netty handshake 内部 retain 的
        // 引用由 WebSocketServerHandshaker$2 消费，此处验证调用方持有的原始引用已被释放。
        // 修复前不释放，每次 WS 握手泄漏 1 个 FullHttpRequest 引用（refCnt 停留在 1）。
        assertEquals(0, req.refCnt(), "握手成功后请求必须被释放，否则每次 WS 握手泄漏一个 FullHttpRequest 引用");

        // finishAndReleaseAll 只清理 outbound（握手响应）与 inbound 缓冲；req 不在其中，
        // 不会双重释放。
        channel.finishAndReleaseAll();
    }

    /**
     * 回归：pipeline 缺少 {@code HttpServerCodec} 时握手失败，请求原始引用仍须归零。
     * <p>
     * 注意这条路径<b>不是</b>同步抛异常：Netty 的 handshaker 检测不到 decoder 时把返回的 promise 置失败
     * （{@code No HttpDecoder and no HttpServerCodec in the pipeline}），由失败 listener 释放请求。
     * 用例名与断言都按这个真实行为写——此前的名字声称验证「同步抛异常」，实际走的是 listener，同步 catch 一行都没执行（JaCoCo 实测该分支 cb=0）。
     * </p>
     */
    @Test
    void handshakeMissingCodec_failsAsynchronously_releasesRequest() {
        EmbeddedChannel channel = new EmbeddedChannel();
        // 故意不加 HttpServerCodec：handshaker 会把 promise 置失败
        channel.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
        channel.pipeline()
                .addLast(new WebSocketRoutingHandler(Map.of("/ws", mock(WebSocketHandler.class)), null, false, null));

        FullHttpRequest req = upgradeRequest("/ws");
        channel.pipeline().fireChannelRead(req);
        channel.runPendingTasks();

        assertEquals(0, req.refCnt(), "握手失败时也必须释放请求，否则泄漏一个 FullHttpRequest");
        assertNoSuccessfulUpgrade(channel);
        channel.finishAndReleaseAll();
    }

    /**
     * 回归：握手内部 pipeline 操作同步抛错时必须释放请求原始引用，且不能泄漏握手响应。
     * <p>
     * 预置同名 {@code wsdecoder} 会让 Netty 的 {@code addBefore} 抛 {@code ChannelPipelineException}。 Netty 在抛错前已创建 101
     * 响应却不会释放它，因此框架在调用 handshake() 之前先检查保留名并 短路失败：既不分配响应，也释放 FullHttpRequest（含 content ByteBuf）并关闭连接。
     * </p>
     */
    @Test
    void handshakeThrowsSynchronously_releasesRequest() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(new HttpServerCodec());
        channel.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
        // 预置同名 handler，使握手内部的 addBefore 抛 ChannelPipelineException
        channel.pipeline().addLast("wsdecoder", new ChannelInboundHandlerAdapter());
        channel.pipeline()
                .addLast(new WebSocketRoutingHandler(Map.of("/ws", mock(WebSocketHandler.class)), null, false, null));

        FullHttpRequest req = upgradeRequest("/ws");
        channel.pipeline().fireChannelRead(req);
        channel.runPendingTasks();

        assertEquals(0, req.refCnt(), "handshake() 同步抛异常时也必须释放请求，否则泄漏一个 FullHttpRequest");
        assertFalse(channel.isOpen(), "同步抛异常后应关闭连接，不再复用该通道");
        assertNoSuccessfulUpgrade(channel);
        channel.finishAndReleaseAll();
    }

    /** 失败路径不得写出 101，也不得挂会话——否则 refCnt==0 无法区分「成功释放」与「失败释放」。 */
    private static void assertNoSuccessfulUpgrade(EmbeddedChannel channel) {
        String response = readOutboundText(channel);
        assertFalse(response.contains("101 Switching Protocols"), "握手失败路径不得写出 101 响应，实际响应：" + response);
        assertNull(WebSocketRoutingHandler.sessionOf(channel), "握手失败时不得建立会话");
    }

    /** 读空 outbound（含 101/403 响应字节）后全部释放，返回拼接的响应文本。 */
    private static String readOutboundText(EmbeddedChannel channel) {
        StringBuilder text = new StringBuilder();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ByteBuf) {
                    text.append(((ByteBuf) outbound).toString(StandardCharsets.UTF_8));
                } else {
                    text.append(outbound);
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return text.toString();
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
