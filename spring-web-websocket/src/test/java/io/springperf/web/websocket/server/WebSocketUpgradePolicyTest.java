package io.springperf.web.websocket.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.springperf.web.websocket.WebSocketHandlerRegistration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link WebSocketUpgradePolicy}：per-path 覆盖解析、Origin 准入与拒绝响应。
 * <p>
 * 这些判定原先散在 {@code WebSocketRoutingHandler} 的私有方法里，只能靠完整握手间接覆盖；抽成策略对象后可直接验证 三态配置（未配置 / 空列表 / 通配）与回退顺序。
 * </p>
 */
class WebSocketUpgradePolicyTest {

    private EmbeddedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null && channel.isOpen()) {
            channel.finishAndReleaseAll();
        }
    }

    private static WebSocketUpgradePolicy newPolicy(List<String> origins) {
        return new WebSocketUpgradePolicy("chat", true, origins, 30_000, 10_000);
    }

    private static FullHttpRequest request(String origin) {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws",
                Unpooled.buffer(0));
        if (origin != null) {
            req.headers().set(HttpHeaderNames.ORIGIN, origin);
        }
        return req;
    }

    @Test
    void resolve_perPathOverridesGlobal_andFallsBackWhenRegistrationOrFieldMissing() {
        WebSocketUpgradePolicy policy = newPolicy(List.of("https://a.example"));
        WebSocketHandlerRegistration reg = new WebSocketHandlerRegistration(mock(WebSocketHandler.class), "/ws")
                .setAllowedOrigins("https://b.example").setSubProtocols("v2").setAllowExtensions(false)
                .setIdleTimeout(1_000).setHeartbeatInterval(2_000).setMessageSizeLimit(4_096);

        // per-path 覆盖：每个字段独立生效
        assertEquals(List.of("https://b.example"), policy.resolveOrigins(reg));
        assertEquals("v2", policy.resolveSubProtocols(reg));
        assertFalse(policy.resolveAllowExtensions(reg));
        assertEquals(1_000, policy.resolveIdleTimeout(reg));
        assertEquals(2_000, policy.resolveHeartbeatInterval(reg));
        assertEquals(4_096, policy.resolveMessageSizeLimit(reg));

        // 无注册信息（精确匹配但未注册）→ 全部回退全局默认
        assertEquals(List.of("https://a.example"), policy.resolveOrigins(null));
        assertEquals("chat", policy.resolveSubProtocols(null));
        assertTrue(policy.resolveAllowExtensions(null));
        assertEquals(30_000, policy.resolveIdleTimeout(null));
        assertEquals(10_000, policy.resolveHeartbeatInterval(null));
        // messageSizeLimit 没有全局默认值，语义是「不覆盖」
        assertEquals(-1, policy.resolveMessageSizeLimit(null));
    }

    @Test
    void resolve_globalOriginsMayBeNull_meaningNoRestriction() {
        assertNull(newPolicy(null).resolveOrigins(null));
    }

    @Test
    void resolveMaxFrameSize_usesPerPathLimitOrLargestDefault() {
        long largestDefault = Math.max(NettyWebSocketSession.DEFAULT_TEXT_MESSAGE_SIZE_LIMIT,
                NettyWebSocketSession.DEFAULT_BINARY_MESSAGE_SIZE_LIMIT);

        assertEquals(largestDefault, newPolicy(null).resolveMaxFrameSize(null));

        WebSocketHandlerRegistration limited = new WebSocketHandlerRegistration(mock(WebSocketHandler.class), "/ws")
                .setMessageSizeLimit(4_096);
        assertEquals(4_096, newPolicy(null).resolveMaxFrameSize(limited));

        // 0 / 负数在注册对象里等价于「未覆盖」，不能作为解码器上限（否则所有帧都超限）
        WebSocketHandlerRegistration zero = new WebSocketHandlerRegistration(mock(WebSocketHandler.class), "/ws")
                .setMessageSizeLimit(0);
        assertEquals(largestDefault, newPolicy(null).resolveMaxFrameSize(zero));
        WebSocketHandlerRegistration negative = new WebSocketHandlerRegistration(mock(WebSocketHandler.class), "/ws")
                .setMessageSizeLimit(-1);
        assertEquals(largestDefault, newPolicy(null).resolveMaxFrameSize(negative));
    }

    @Test
    void checkOrigin_unconfiguredAllowsEverything() {
        WebSocketUpgradePolicy policy = newPolicy(null);
        assertTrue(policy.checkOrigin(request("https://evil.example"), null));
        assertTrue(policy.checkOrigin(request(null), null));
    }

    @Test
    void checkOrigin_emptyListRejectsBrowserButAllowsMissingOriginHeader() {
        WebSocketUpgradePolicy policy = newPolicy(List.of());
        // 显式空列表 = 只允许不带 Origin 的非浏览器客户端
        assertFalse(policy.checkOrigin(request("https://a.example"), null));
        assertTrue(policy.checkOrigin(request(null), null));
    }

    @Test
    void checkOrigin_wildcardAndExactMatchAccepted_otherOriginsRejected() {
        assertTrue(newPolicy(List.of("*")).checkOrigin(request("https://any.example"), null));
        WebSocketUpgradePolicy exact = newPolicy(List.of("https://a.example"));
        assertTrue(exact.checkOrigin(request("https://a.example"), null));
        assertFalse(exact.checkOrigin(request("https://b.example"), null));
    }

    @Test
    void checkOrigin_perPathConfigurationWins() {
        // 全局拒绝，per-path 放行
        WebSocketUpgradePolicy policy = newPolicy(List.of("https://a.example"));
        WebSocketHandlerRegistration allowB = new WebSocketHandlerRegistration(mock(WebSocketHandler.class), "/ws")
                .setAllowedOrigins("https://b.example");
        assertTrue(policy.checkOrigin(request("https://b.example"), allowB));

        // 全局放行，per-path 拒绝（空列表）
        WebSocketUpgradePolicy permissive = newPolicy(null);
        WebSocketHandlerRegistration denyAll = new WebSocketHandlerRegistration(mock(WebSocketHandler.class), "/ws")
                .setAllowedOrigins();
        assertFalse(permissive.checkOrigin(request("https://b.example"), denyAll));
    }

    @Test
    void checkHandlerOrigin_ignoresHandlersWithoutValidator() {
        assertTrue(WebSocketUpgradePolicy.checkHandlerOrigin(mock(WebSocketHandler.class), "https://a.example"));
    }

    @Test
    void checkHandlerOrigin_reportsValidatorDecision_andTreatsThrowingValidatorAsRejection() {
        assertTrue(WebSocketUpgradePolicy.checkHandlerOrigin(originValidator(true), "https://a.example"));
        assertFalse(WebSocketUpgradePolicy.checkHandlerOrigin(originValidator(false), "https://a.example"));
        // 用户判定抛异常时必须按拒绝处理：放异常逃逸会让客户端拿不到任何响应，只能等超时
        assertFalse(WebSocketUpgradePolicy.checkHandlerOrigin(originValidator(null), "https://a.example"));
    }

    /** 同时是 handler 与 Origin 校验扩展点；{@code allowed=null} 表示判定过程抛异常。 */
    private static WebSocketHandler originValidator(Boolean allowed) {
        return new ValidatorHandler(allowed);
    }

    private static final class ValidatorHandler implements WebSocketHandler, WebSocketOriginValidator {

        private final Boolean allowed;

        ValidatorHandler(Boolean allowed) {
            this.allowed = allowed;
        }

        @Override
        public boolean isOriginAllowed(String origin) {
            if (allowed == null) {
                throw new IllegalStateException("configurator blew up");
            }
            return allowed;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }

    @Test
    void sendForbiddenResponse_writes403WithCloseAndReleasesRequest() {
        channel = new EmbeddedChannel();
        channel.pipeline().addLast("probe", new ChannelInboundHandlerAdapter());
        FullHttpRequest req = request("https://evil.example");

        WebSocketUpgradePolicy.sendForbiddenResponse(channel.pipeline().context("probe"), req);

        HttpResponse resp = channel.readOutbound();
        assertEquals(HttpResponseStatus.FORBIDDEN, resp.status());
        // keep-alive 请求也要显式关闭，避免拒绝后连接被复用
        assertEquals("close", resp.headers().get(HttpHeaderNames.CONNECTION));
        // 拒绝路径不透传请求，必须自己释放（否则每次拒绝泄漏一个引用）
        assertEquals(0, req.refCnt());
    }
}
