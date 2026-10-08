package io.springperf.web.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.springperf.web.context.ApplicationProperties;

/**
 * {@link SupportHttpContentCompressor} 的边界分支单元测试。
 * <p>
 * 为什么需要：该类的三条自定义逻辑（MIME 白名单、UA 排除、零拷贝跳过）此前<b>只有 E2E 覆盖</b> （{@code CompressionE2ETest} 等，走全链路）。E2E
 * 证明了主行为正确，却难以稳定触达以下边界：
 * <ul>
 * <li>{@code Vary: Accept-Encoding} 的<b>幂等/追加</b>分支（已有其它 Vary 值、大小写差异）</li>
 * <li>Content-Type 的 <b>{@code ;charset} 参数剥离</b>与<b>大小写归一兜底</b></li>
 * <li>{@code compressionSkip} 的<b>读后清零</b>语义（防同一连接的下一个响应被误跳过）</li>
 * </ul>
 * 用 {@link EmbeddedChannel} 驱动真实 handler（而非 mock 上下文），断言的就是生产代码路径。
 * </p>
 * <p>
 * 注意输出形态：压缩命中时 Netty 的 {@code HttpContentEncoder} 会拆成 <b>普通 {@code HttpResponse}（头）+ {@code HttpContent}（体）</b>，不再是
 * {@code FullHttpResponse}； 未压缩时才原样透传 {@code FullHttpResponse}。故读出的头一律按 {@link HttpResponse} 处理。
 * </p>
 */
class SupportHttpContentCompressorTest {

    /** 默认白名单含 text/html；阈值设 0 以便小响应也走压缩，便于观察 Vary 与跳过的差异。 */
    private static CompressionConfig config(String... kv) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("server.compression.enabled", "true");
        env.setProperty("server.compression.min-response-size", "0");
        for (String s : kv) {
            int idx = s.indexOf('=');
            env.setProperty(s.substring(0, idx), s.substring(idx + 1));
        }
        ApplicationProperties props = new ApplicationProperties();
        props.setEnvironment(env);
        return CompressionConfig.fromProperties(props);
    }

    private static EmbeddedChannel channel(CompressionConfig cfg) {
        return new EmbeddedChannel(new SupportHttpContentCompressor(cfg));
    }

    /** 发一个声明 Accept-Encoding: gzip 的请求（父类据此协商压缩）。 */
    private static void requestGzip(EmbeddedChannel ch, String userAgent) {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        req.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
        if (userAgent != null) {
            req.headers().set(HttpHeaderNames.USER_AGENT, userAgent);
        }
        ch.writeInbound(req);
    }

    private static FullHttpResponse okWithContentType(String contentType, String body) {
        ByteBuf content = Unpooled.copiedBuffer(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        if (contentType != null) {
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        return resp;
    }

    /** 写出响应并取回「头」对象（压缩命中时是 HttpResponse，未压缩时是 FullHttpResponse）。 */
    private static HttpResponse writeAndReadHeaders(EmbeddedChannel ch, FullHttpResponse resp) {
        ch.writeOutbound(resp);
        return ch.readOutbound();
    }

    // ---------- Content-Type 主类型：;charset 剥离、大小写归一 ----------

    @Test
    void contentTypeWithCharsetParameter_isStillCompressed() {
        EmbeddedChannel ch = channel(config());
        requestGzip(ch, null);
        HttpResponse out = writeAndReadHeaders(ch, okWithContentType("text/html;charset=UTF-8", "x".repeat(100)));
        assertThat(out.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
    }

    @Test
    void contentTypeUppercase_isCompressedViaFallbackNormalisation() {
        // 白名单是小写的 text/html；此处故意大写，验证 toLowerCase 兜底分支
        EmbeddedChannel ch = channel(config());
        requestGzip(ch, null);
        HttpResponse out = writeAndReadHeaders(ch, okWithContentType("TEXT/HTML", "x".repeat(100)));
        assertThat(out.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
    }

    @Test
    void contentTypeNotInWhitelist_isNotCompressed() {
        EmbeddedChannel ch = channel(config());
        requestGzip(ch, null);
        HttpResponse out = writeAndReadHeaders(ch, okWithContentType("application/octet-stream", "x".repeat(100)));
        assertThat(out.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
    }

    @Test
    void contentTypeAbsent_isNotCompressed() {
        EmbeddedChannel ch = channel(config());
        requestGzip(ch, null);
        HttpResponse out = writeAndReadHeaders(ch, okWithContentType(null, "x".repeat(100)));
        assertThat(out.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
    }

    // ---------- Vary: Accept-Encoding 的幂等与追加 ----------

    @Test
    void varyAppended_whenResponseAlreadyVaries() {
        EmbeddedChannel ch = channel(config());
        requestGzip(ch, null);
        FullHttpResponse resp = okWithContentType("text/html", "x".repeat(100));
        resp.headers().set(HttpHeaderNames.VARY, "Accept-Language");
        HttpResponse out = writeAndReadHeaders(ch, resp);
        // Netty 的 HttpHeaders 会规范化 header 值（此处 accept-encoding 变小写），故大小写不敏感断言
        assertThat(out.headers().get(HttpHeaderNames.VARY)).containsIgnoringCase("Accept-Language")
                .containsIgnoringCase("Accept-Encoding");
    }

    @Test
    void varyNotDuplicated_whenAlreadyPresent() {
        EmbeddedChannel ch = channel(config());
        requestGzip(ch, null);
        FullHttpResponse resp = okWithContentType("text/html", "x".repeat(100));
        resp.headers().set(HttpHeaderNames.VARY, "Accept-Encoding");
        HttpResponse out = writeAndReadHeaders(ch, resp);
        // 幂等：不得出现两次
        assertThat(out.headers().get(HttpHeaderNames.VARY)).isEqualTo("Accept-Encoding");
    }

    @Test
    void varyNotDuplicated_caseInsensitiveMatch() {
        // 大小写不同也算「已声明」，不应重复追加
        EmbeddedChannel ch = channel(config());
        requestGzip(ch, null);
        FullHttpResponse resp = okWithContentType("text/html", "x".repeat(100));
        resp.headers().set(HttpHeaderNames.VARY, "accept-encoding");
        HttpResponse out = writeAndReadHeaders(ch, resp);
        assertThat(out.headers().get(HttpHeaderNames.VARY)).isEqualTo("accept-encoding");
    }

    @Test
    void varyContainingSubstring_isTreatedAsPresent_notAppended() {
        // 当前实现用 contains 判定：含 "Accept-Encoding" 子串即视为已声明。
        // 把该行为钉住（记录用）——「X-Accept-Encoding-Y」会被误判为已含，
        // 属已知的宽松判定，将来若收紧应让此用例变红以提醒改动。
        EmbeddedChannel ch = channel(config());
        requestGzip(ch, null);
        FullHttpResponse resp = okWithContentType("text/html", "x".repeat(100));
        resp.headers().set(HttpHeaderNames.VARY, "X-Accept-Encoding-Y");
        HttpResponse out = writeAndReadHeaders(ch, resp);
        assertThat(out.headers().get(HttpHeaderNames.VARY)).isEqualTo("X-Accept-Encoding-Y");
    }

    // ---------- User-Agent 正则排除 ----------
    //
    // 注意：UA 不来自出站响应（响应头不含 UA），而是由 NettyHttpHandler 在入站请求路径
    // 写入 ChannelAttrs.compressionReqUa（NettyHttpHandler:366），压缩器再读走并清零。
    // 这是**跨 handler 的交接**，故单测必须直接设置该属性，而不是靠 writeInbound 的请求头。

    @Test
    void excludedUserAgent_isNotCompressed() {
        EmbeddedChannel ch = channel(config("server.compression.excluded-user-agents=BadBot"));
        ChannelAttrs.of(ch).compressionReqUa = "Mozilla/5.0 BadBot/1.0";
        requestGzip(ch, null);
        HttpResponse out = writeAndReadHeaders(ch, okWithContentType("text/html", "x".repeat(100)));
        assertThat(out.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
    }

    @Test
    void nonMatchingUserAgent_isCompressed() {
        EmbeddedChannel ch = channel(config("server.compression.excluded-user-agents=BadBot"));
        ChannelAttrs.of(ch).compressionReqUa = "Mozilla/5.0 GoodAgent/1.0";
        requestGzip(ch, null);
        HttpResponse out = writeAndReadHeaders(ch, okWithContentType("text/html", "x".repeat(100)));
        assertThat(out.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
    }

    @Test
    void userAgentIsClearedAfterUse_soNextResponseNotExcluded() {
        // 读后清零：否则同一连接的下一个响应会被上一个请求的 UA 误排除
        EmbeddedChannel ch = channel(config("server.compression.excluded-user-agents=BadBot"));
        ChannelAttrs attrs = ChannelAttrs.of(ch);
        attrs.compressionReqUa = "BadBot";

        requestGzip(ch, null);
        HttpResponse first = writeAndReadHeaders(ch, okWithContentType("text/html", "x".repeat(100)));
        assertThat(first.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        assertThat(attrs.compressionReqUa).as("UA 读后必须清零").isNull();

        // 下一个请求是正常 UA → 应恢复压缩
        attrs.compressionReqUa = "GoodAgent";
        requestGzip(ch, null);
        HttpResponse second = writeAndReadHeaders(ch, okWithContentType("text/html", "x".repeat(100)));
        assertThat(second.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
    }

    // ---------- compressionSkip 的读后清零 ----------

    @Test
    void compressionSkip_setForNextResponse_onlySkipsOnce() {
        EmbeddedChannel ch = channel(config());
        ChannelAttrs attrs = ChannelAttrs.of(ch);
        attrs.compressionSkip = true;

        requestGzip(ch, null);
        // 第一次响应：带 skip 标记 → 透传不压缩，且标记被清零
        HttpResponse first = writeAndReadHeaders(ch, okWithContentType("text/html", "x".repeat(100)));
        assertThat(first.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        assertThat(attrs.compressionSkip).as("skip 标记读后必须清零").isFalse();

        // 第二次响应：同一连接、标记已清 → 恢复正常压缩
        requestGzip(ch, null);
        HttpResponse second = writeAndReadHeaders(ch, okWithContentType("text/html", "x".repeat(100)));
        assertThat(second.headers().get(HttpHeaderNames.CONTENT_ENCODING)).as("skip 只应影响一次响应").isEqualTo("gzip");
    }

    @Test
    void noChannelAttrs_doesNotThrowAndCompresses() {
        // 未走过请求路径的连接（无持有者）：attrs == null 分支不应 NPE
        EmbeddedChannel ch = channel(config());
        requestGzip(ch, null);
        HttpResponse out = writeAndReadHeaders(ch, okWithContentType("text/html", "x".repeat(100)));
        assertThat(out.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
    }

    // ---------- 未声明 Accept-Encoding 时不压缩 ----------

    @Test
    void noAcceptEncoding_isNotCompressed() {
        EmbeddedChannel ch = channel(config());
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        ch.writeInbound(req); // 不带 Accept-Encoding
        HttpResponse out = writeAndReadHeaders(ch, okWithContentType("text/html", "x".repeat(100)));
        assertThat(out.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
    }
}
