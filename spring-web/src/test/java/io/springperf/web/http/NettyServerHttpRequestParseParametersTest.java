package io.springperf.web.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * 回归 P2 性能组 #1：parseParameters 的 urlencoded 分支。
 * <p>修复前 Content-Type 匹配用 {@code toLowerCase() + startsWith}（每请求分配小写串），
 * 且 {@code getBodyHttpDatas()} 被调用两次（line 90 与 line 94 各一次）。
 * 修复后改 {@code regionMatches}（不分配）并复用已取的 bodyHttpDataList——行为必须不变。</p>
 */
@ExtendWith(MockitoExtension.class)
class NettyServerHttpRequestParseParametersTest {

    @Mock
    private WebContext webContext;
    @Mock
    private ChannelHandlerContext ctx;
    @Mock
    private ApplicationProperties props;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(props.getInt(anyString())).thenReturn(4096);
    }

    @Test
    void parseParameters_urlencodedBody_parsesFields() {
        FullHttpRequest nativeRequest = newRequest("a=1&b=two&a=3");
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        MultiValueMap<String, String> params = req.getParameterMap();

        assertEquals("1", params.getFirst("a"));
        assertEquals("two", params.getFirst("b"));
        assertEquals(2, params.get("a").size());
        assertTrue(params.get("a").contains("3"));
    }

    @Test
    void parseParameters_urlencodedContentType_mixedCase_matches() {
        // 修复后 regionMatches 大小写不敏感：Content-Type 带大写 X-WWW- 也必须命中
        FullHttpRequest nativeRequest = newRequest("name=hi");
        nativeRequest.headers().set(HttpHeaderNames.CONTENT_TYPE,
                "Application/X-WWW-Form-Urlencoded; charset=UTF-8");
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        assertEquals("hi", req.getParameterMap().getFirst("name"));
    }

    @Test
    void parseParameters_urlencodedEmptyBody_returnsEmptyMap() {
        FullHttpRequest nativeRequest = newRequest("");
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        assertTrue(req.getParameterMap().isEmpty());
    }

    @Test
    void parseParameters_noContentType_returnsEmptyMap() {
        FullHttpRequest nativeRequest = newRequest("a=1");
        nativeRequest.headers().remove(HttpHeaderNames.CONTENT_TYPE);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        assertTrue(req.getParameterMap().isEmpty());
    }

    @Test
    void parseParameters_queryString_only_parsesQuery() {
        // query 参数走 QueryStringDecoder 路径（非 body 解析）
        FullHttpRequest nativeRequest = newRequest("", "/test?q=hello");
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test?q=hello");

        assertEquals("hello", req.getParameterMap().getFirst("q"));
    }

    @Test
    void parseParameters_urlencodedBody_plusQuery_merges() {
        // body 字段与 query 字段合并：body 先 add、query 经 result.addAll 追加
        FullHttpRequest nativeRequest = newRequest("from=body", "/test?from=query&q=1");
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test?from=query&q=1");

        MultiValueMap<String, String> params = req.getParameterMap();
        assertEquals("body", params.getFirst("from"));
        assertEquals(2, params.get("from").size());
        assertEquals("1", params.getFirst("q"));
    }

    private static FullHttpRequest newRequest(String bodyContent) {
        return newRequest(bodyContent, "/test");
    }

    private static FullHttpRequest newRequest(String bodyContent, String uri) {
        byte[] bodyBytes = bodyContent.getBytes(StandardCharsets.UTF_8);
        ByteBuf content = Unpooled.copiedBuffer(bodyBytes);
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, content);
        if (!bodyContent.isEmpty()) {
            req.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
        }
        return req;
    }
}
