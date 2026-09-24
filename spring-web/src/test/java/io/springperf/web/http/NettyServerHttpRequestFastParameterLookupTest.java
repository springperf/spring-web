package io.springperf.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;

/**
 * {@code @RequestParam} 单值解析的「查询串直扫」快路径回归测试。
 * <p>
 * 快路径（{@code NettyServerHttpRequest#getParameter}）只在「无 body 的纯查询请求 + 查询串 不含
 * {@code % + ; #}」时启用，其余情况必须<b>与通用路径语义完全一致</b>。本类逐条锁定： 命中/未命中/空值、同名多值取首个、转义与分号分隔回退、段数超阈回退、参数上限仍抛 400（hash DoS 防护）、 以及
 * POST 表单体不被快路径绕过。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class NettyServerHttpRequestFastParameterLookupTest {

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
        lenient().when(props.getMaxParameterCount()).thenReturn(0);
    }

    @Test
    void fastPath_simpleQuery_hitsAndEmptyAndMiss() {
        NettyServerHttpRequest req = get("/test?p1=1&p2=&p3", "/test");

        assertEquals("1", req.getParameter("p1"));
        assertEquals("", req.getParameter("p2"), "缺 '=' 的值应为空串（对齐 Netty 解码）");
        assertEquals("", req.getParameter("p3"));
        assertNull(req.getParameter("p4"), "不存在的参数返回 null");
        assertNull(req.getParameter(""), "空名字不应命中任何段");
    }

    @Test
    void fastPath_sameNameMultipleValues_returnsFirst() {
        NettyServerHttpRequest req = get("/test?a=1&a=2&a=3", "/test");

        assertEquals("1", req.getParameter("a"));
    }

    /** 快路径与通用路径必须给出相同结果（等价的直接证据）。 */
    @Test
    void fastPath_matchesGenericPath_forSimpleQuery() {
        NettyServerHttpRequest req = get("/test?x=1&y=two&z=", "/test");

        for (String name : new String[] { "x", "y", "z", "absent" }) {
            assertEquals(req.getParameterMap().getFirst(name), req.getParameter(name), "参数 " + name + " 的快/慢路径结果必须一致");
        }
    }

    @Test
    void escapedQuery_fallsBackToDecoder_stillDecodesCorrectly() {
        // 含 '%' 与 '+': 不走快路径，由 QueryStringDecoder 解码
        NettyServerHttpRequest req = get("/test?a=%31&b=x+y", "/test");

        assertEquals("1", req.getParameter("a"));
        assertEquals("x y", req.getParameter("b"), "'+' 必须解码为空格");
    }

    @Test
    void semicolonSeparatedQuery_fallsBackToDecoder() {
        // 含 ';': Netty 视为分隔符 —— 快路径必须让位，语义保持不变
        NettyServerHttpRequest req = get("/test?a=1;b=2", "/test");

        assertEquals("1", req.getParameter("a"));
        assertEquals("2", req.getParameter("b"));
    }

    @Test
    void tooManySegments_fallsBackAndStillResolvesAll() {
        StringBuilder query = new StringBuilder("/test?");
        for (int i = 0; i < 12; i++) {
            query.append("p").append(i).append('=').append(i).append('&');
        }
        NettyServerHttpRequest req = get(query.toString(), "/test");

        assertEquals("0", req.getParameter("p0"));
        assertEquals("11", req.getParameter("p11"), "超出快路径段数上限后必须回退且结果仍完整");
    }

    /** hash DoS 防护在快路径下必须仍然生效（回退通用路径后抛同一异常）。 */
    @Test
    void parameterLimitExceeded_stillThrows() {
        lenient().when(props.getMaxParameterCount()).thenReturn(3);
        NettyServerHttpRequest req = get("/test?a=1&b=2&c=3&d=4", "/test");

        assertThrows(ParameterLimitExceededException.class, () -> req.getParameter("a"), "参数数超限必须抛 400（不允许快路径绕过上限校验）");
    }

    /** 带表单体的 POST：不得走快路径（body 字段优先于 query，与合并语义一致）。 */
    @Test
    void postFormBody_usesGenericMergeSemantics() {
        NettyServerHttpRequest req = post("from=body&q=1", "/test?from=query&q=query");

        assertEquals("body", req.getParameter("from"), "body 字段优先（合并语义：body 先入）");
        assertEquals("1", req.getParameter("q"));
    }

    /** 有 body 的请求即使查询串很简单也必须走通用路径（contentLength>0 即不可快路径）。 */
    @Test
    void requestWithBody_neverUsesFastPath() {
        NettyServerHttpRequest req = post("", "/test?a=1");

        assertEquals("1", req.getParameter("a"));
    }

    private NettyServerHttpRequest get(String uri, String resolvedPath) {
        return new NettyServerHttpRequest(webContext, ctx, newRequest(HttpMethod.GET, "", uri), resolvedPath);
    }

    private NettyServerHttpRequest post(String body, String uri) {
        return new NettyServerHttpRequest(webContext, ctx, newRequest(HttpMethod.POST, body, uri), "/test");
    }

    private static FullHttpRequest newRequest(HttpMethod method, String body, String uri) {
        ByteBuf content = Unpooled.copiedBuffer(body.getBytes(StandardCharsets.UTF_8));
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, content);
        if (!body.isEmpty()) {
            req.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
        }
        return req;
    }
}
