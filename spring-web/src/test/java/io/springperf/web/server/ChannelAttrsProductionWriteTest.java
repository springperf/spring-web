package io.springperf.web.server;

import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelAttrs} 里那几个「生产写入、测试读取」字段的**真实写入方**守卫。
 * <p>
 * 为什么需要它：单测为了构造场景会<b>直接赋值</b>（如 {@code attrs.pipeliningInFlight = true}），这类测试无法发现
 * 「生产代码哪天不再写这个字段」——字段一直是测试自己设的，照样绿。本类改为驱动真实入站路径
 * （{@code NettyHttpHandler.channelRead}），并在**委托给分发器的那一刻**读取字段，因此生产侧停止赋值即变红。
 * </p>
 * <p>
 * 覆盖三个字段：{@code inFlightRequest}（断连兜底释放在途请求靠它）、{@code pipeliningInFlight}（读空闲豁免与排队靠它）、 {@code compressionReqUa}（压缩器 UA
 * 排除靠它，且仅在启用压缩时写入）。第四个字段 {@code compressionSkip} 的写入方是 {@code NettyServerHttpResponse.writeFile}，其行为已由
 * {@code CompressionE2ETest} 的「writeFile 必须跳过压缩 + 字节无损」覆盖。
 * </p>
 */
class ChannelAttrsProductionWriteTest {

    /** 在分发器被调用的那一刻抓取字段值：此后 handleRequest 的 finally 会清掉在途登记。 */
    private static ChannelAttrs captureAtDispatch(EmbeddedChannel channel, boolean compressionEnabled)
            throws Exception {
        ApplicationProperties props = new ApplicationProperties();
        props.setEnvironment(new MockEnvironment());
        WebContext webContext = mock(WebContext.class);
        when(webContext.getProps()).thenReturn(props);
        when(webContext.getWebComponent(any())).thenReturn(null);

        AtomicReference<ChannelAttrs> captured = new AtomicReference<>();
        HttpHandler delegate = mock(HttpHandler.class);
        doAnswer(invocation -> {
            captured.set(ChannelAttrs.of(channel));
            return null;
        }).when(delegate).httpHandle(any(WebServerHttpRequest.class), any(WebServerHttpResponse.class));

        NettyHttpHandler handler = new NettyHttpHandler(webContext, "", delegate, compressionEnabled,
                ResponseLimitConfig.fromProperties(props));
        channel.pipeline().addLast(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/guard",
                Unpooled.EMPTY_BUFFER);
        request.headers().set(HttpHeaderNames.USER_AGENT, "GuardBot/1.0");
        channel.writeInbound(request);
        return captured.get();
    }

    @Test
    void channelRead_writesInFlightRequest_andPipeliningInFlight() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            ChannelAttrs atDispatch = captureAtDispatch(channel, false);
            assertThat(atDispatch).as("分发器必须在真实入站路径上被调用").isNotNull();
            assertThat(atDispatch.inFlightRequest).as("在途请求登记必须由 NettyHttpHandler 写入（断连兜底依赖它）").isNotNull();
            assertThat(atDispatch.pipeliningInFlight).as("处理中的标记必须由 NettyHttpHandler 写入（读空闲豁免依赖它）").isTrue();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void channelRead_writesRequestUserAgent_whenCompressionEnabled() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            ChannelAttrs atDispatch = captureAtDispatch(channel, true);
            assertThat(atDispatch).isNotNull();
            assertThat(atDispatch.compressionReqUa).as("启用压缩时，请求 UA 必须被带入连接状态供压缩器读取").isEqualTo("GuardBot/1.0");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void channelRead_leavesRequestUserAgentUnset_whenCompressionDisabled() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            ChannelAttrs atDispatch = captureAtDispatch(channel, false);
            assertThat(atDispatch).isNotNull();
            assertThat(atDispatch.compressionReqUa).as("未启用压缩时不应写入 UA（管线里没有压缩器会读它）").isNull();
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
