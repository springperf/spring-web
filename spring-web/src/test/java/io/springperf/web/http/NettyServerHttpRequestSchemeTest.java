package io.springperf.web.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link NettyServerHttpRequest#resolveScheme} 的 scheme 判定安全逻辑：
 * <ol>
 *   <li>转发头默认不信任（use-forwarded-headers=false 时忽略 Forwarded/X-Forwarded-Proto）</li>
 *   <li>开启转发头后优先 RFC 7239 Forwarded，其次 X-Forwarded-Proto</li>
 *   <li>pipeline 存在 SslHandler 时判定 https</li>
 *   <li>兜底 http</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class NettyServerHttpRequestSchemeTest {

    @Mock WebContext webContext;
    @Mock ChannelHandlerContext ctx;
    @Mock ChannelPipeline pipeline;
    @Mock ApplicationProperties props;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(ctx.pipeline()).thenReturn(pipeline);
        lenient().when(props.getInt(anyString())).thenReturn(4096);
        lenient().when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
    }

    private FullHttpRequest newRequest(String host, String... headers) {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/test", Unpooled.buffer(0));
        if (host != null) {
            req.headers().set("Host", host);
        }
        for (int i = 0; i < headers.length; i += 2) {
            req.headers().set(headers[i], headers[i + 1]);
        }
        return req;
    }

    private URI getUri(FullHttpRequest req) {
        NettyServerHttpRequest perf = new NettyServerHttpRequest(webContext, ctx, req, "/test");
        return perf.getURI();
    }

    @Test
    void default_noForwardedHeader_usesHostAndHttp() {
        FullHttpRequest req = newRequest("example.com:8080");
        URI uri = getUri(req);
        assertEquals("http", uri.getScheme());
        assertEquals("example.com", uri.getHost());
        assertEquals(8080, uri.getPort());
        assertEquals("/test", uri.getPath());
        req.release();
    }

    @Test
    void default_forwardedHeadersIgnored() {
        // 默认不信任转发头：即使客户端伪造 Forwarded/X-Forwarded-Proto 也判定为 http
        when(props.getBoolean(PropertiesConstant.USE_FORWARDED_HEADERS, false)).thenReturn(false);
        FullHttpRequest req = newRequest("example.com",
                "Forwarded", "proto=https; host=attacker.com",
                "X-Forwarded-Proto", "https");
        assertEquals("http", getUri(req).getScheme());
        req.release();
    }

    @Test
    void useForwarded_true_rfc7239ForwardedProto() {
        when(props.getBoolean(PropertiesConstant.USE_FORWARDED_HEADERS, false)).thenReturn(true);
        FullHttpRequest req = newRequest("example.com",
                "Forwarded", "proto=https; host=proxy.com");
        assertEquals("https", getUri(req).getScheme());
        req.release();
    }

    @Test
    void useForwarded_true_forwardedQuotedProto() {
        when(props.getBoolean(PropertiesConstant.USE_FORWARDED_HEADERS, false)).thenReturn(true);
        FullHttpRequest req = newRequest("example.com",
                "Forwarded", "for=192.0.2.60;proto=\"https\";host=example.com");
        assertEquals("https", getUri(req).getScheme());
        req.release();
    }

    @Test
    void useForwarded_true_fallsBackToXForwardedProto() {
        when(props.getBoolean(PropertiesConstant.USE_FORWARDED_HEADERS, false)).thenReturn(true);
        FullHttpRequest req = newRequest("example.com",
                "X-Forwarded-Proto", "https");
        assertEquals("https", getUri(req).getScheme());
        req.release();
    }

    @Test
    void useForwarded_true_forwardedProtoMissing_returnsHttp() {
        when(props.getBoolean(PropertiesConstant.USE_FORWARDED_HEADERS, false)).thenReturn(true);
        // Forwarded 头存在但不含 proto，且无 X-Forwarded-Proto
        FullHttpRequest req = newRequest("example.com",
                "Forwarded", "for=192.0.2.60");
        assertEquals("http", getUri(req).getScheme());
        req.release();
    }

    @Test
    void sslHandlerInPipeline_returnsHttps() {
        when(props.getBoolean(PropertiesConstant.USE_FORWARDED_HEADERS, false)).thenReturn(false);
        when(pipeline.get(SslHandler.class)).thenReturn(mock(SslHandler.class));
        FullHttpRequest req = newRequest("example.com");
        assertEquals("https", getUri(req).getScheme());
        req.release();
    }

    @Test
    void noHostHeader_usesLocalAddressForUri() {
        io.netty.channel.Channel channel = mock(io.netty.channel.Channel.class);
        when(channel.localAddress()).thenReturn(new java.net.InetSocketAddress("127.0.0.1", 9090));
        when(ctx.channel()).thenReturn(channel);
        FullHttpRequest req = newRequest(null);
        URI uri = getUri(req);
        assertEquals("http", uri.getScheme());
        assertEquals("127.0.0.1", uri.getHost());
        assertEquals(9090, uri.getPort());
        assertEquals("/test", uri.getPath());
        req.release();
    }
}
