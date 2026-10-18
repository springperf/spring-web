package io.springperf.web.websocket.server;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtension;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketExtension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebSocketExtensionNegotiator}：{@code Sec-WebSocket-Extensions} 的解析、协商与回写。
 * <p>
 * 握手期只走「客户端发一条 {@code permessage-deflate}」这一种最简单形态，故解析规则的边界（空条目、引号参数、 无值 {@code *_window_bits}）在此单独覆盖。
 * </p>
 */
class WebSocketExtensionNegotiatorTest {

    private static HttpHeaders headers(String... values) {
        HttpHeaders headers = new DefaultHttpHeaders();
        for (String value : values) {
            headers.add(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, value);
        }
        return headers;
    }

    @Test
    void negotiate_noExtensionHeader_returnsNull() {
        assertNull(WebSocketExtensionNegotiator.negotiate(new DefaultHttpHeaders()));
    }

    @Test
    void negotiate_unsupportedOfferOnly_returnsNull() {
        assertNull(WebSocketExtensionNegotiator.negotiate(headers("x-unknown; a=1")));
    }

    @Test
    void negotiate_skipsUnsupportedEntriesUntilSupportedOne() {
        WebSocketServerExtension extension = WebSocketExtensionNegotiator
                .negotiate(headers("x-unknown, permessage-deflate"));
        assertNotNull(extension, "同一头值里排在后面的受支持扩展也必须被采用");
        assertEquals("permessage-deflate", extension.newReponseData().name());
    }

    @Test
    void negotiate_scansAllHeaderValues() {
        WebSocketServerExtension extension = WebSocketExtensionNegotiator
                .negotiate(headers("x-unknown", "permessage-deflate"));
        assertNotNull(extension, "第一个头值无匹配时要继续看后续头值");
    }

    @Test
    void negotiate_browserStyleValuelessWindowBits_isIgnoredAndSucceeds() {
        // 浏览器常发 `client_max_window_bits`（无值）：直接交给 Netty 会 parseInt("") 抛异常，
        // 解析阶段丢掉该参数等价于「不回它」，对端按 15 位默认窗口处理。
        WebSocketServerExtension extension = WebSocketExtensionNegotiator
                .negotiate(headers("permessage-deflate; client_max_window_bits"));
        assertNotNull(extension);
    }

    @Test
    void negotiate_quotedParameterWithComma_keepsValueIntact() {
        // 引号值里的逗号不能当成条目分隔符
        List<WebSocketExtensionData> parsed = WebSocketExtensionNegotiator
                .parseHeader("permessage-deflate; x=\"a,b\"; y=\"q;r\"");
        assertEquals(1, parsed.size());
        assertEquals("a,b", parsed.get(0).parameters().get("x"));
        assertEquals("q;r", parsed.get(0).parameters().get("y"));
    }

    @Test
    void parseHeader_skipsEmptyEntriesAndEmptyTokens() {
        List<WebSocketExtensionData> parsed = WebSocketExtensionNegotiator.parseHeader(" , permessage-deflate ;;  ");
        assertEquals(1, parsed.size(), "空条目与空 token 都不产生扩展");
        assertEquals("permessage-deflate", parsed.get(0).name());
        assertTrue(parsed.get(0).parameters().isEmpty());
    }

    @Test
    void parseHeader_keepsValuelessBooleanButDropsValuelessWindowBits() {
        List<WebSocketExtensionData> parsed = WebSocketExtensionNegotiator
                .parseHeader("permessage-deflate; no_context_takeover; client_max_window_bits");

        Map<String, String> parameters = parsed.get(0).parameters();
        // 布尔开关：Netty 用 containsKey 判断，必须保留且值为空串
        assertTrue(parameters.containsKey("no_context_takeover"));
        assertEquals("", parameters.get("no_context_takeover"));
        // 数值参数：无值形式会被 Netty 的 parseInt("") 打挂，必须在解析阶段丢弃
        assertFalse(parameters.containsKey("client_max_window_bits"));
    }

    @Test
    void parseHeader_readsValuedParameterAndStripsQuotes() {
        List<WebSocketExtensionData> parsed = WebSocketExtensionNegotiator
                .parseHeader("permessage-deflate; server_max_window_bits=9; note=\"quoted\"");
        assertEquals("9", parsed.get(0).parameters().get("server_max_window_bits"));
        assertEquals("quoted", parsed.get(0).parameters().get("note"));
    }

    @Test
    void parseHeader_quoteStrippingBoundaries() {
        // 长度 < 2（单字符 / 空引号对）、长度够但首字符非引号、只有开头是引号：都不能走 substring(1, len-1)
        assertEquals("a",
                WebSocketExtensionNegotiator.parseHeader("permessage-deflate; x=a").get(0).parameters().get("x"));
        assertEquals("",
                WebSocketExtensionNegotiator.parseHeader("permessage-deflate; x=\"\"").get(0).parameters().get("x"));
        assertEquals("ab",
                WebSocketExtensionNegotiator.parseHeader("permessage-deflate; x=ab").get(0).parameters().get("x"));
        assertEquals("\"ab",
                WebSocketExtensionNegotiator.parseHeader("permessage-deflate; x=\"ab").get(0).parameters().get("x"),
                "引号未成对时不能裁掉首尾字符");
    }

    @Test
    void responseHeaderValue_joinsNameAndParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("no_context_takeover", "");
        parameters.put("server_max_window_bits", "10");

        String value = WebSocketExtensionNegotiator
                .responseHeaderValue(new WebSocketExtensionData("permessage-deflate", parameters));

        // 空值参数只写名字（不能出现 `no_context_takeover=`）
        assertEquals("permessage-deflate; no_context_takeover; server_max_window_bits=10", value);
    }

    @Test
    void responseHeaderValue_withoutParameters_isNameOnly() {
        assertEquals("permessage-deflate", WebSocketExtensionNegotiator
                .responseHeaderValue(new WebSocketExtensionData("permessage-deflate", Collections.emptyMap())));
    }

    @Test
    void toSpringExtension_copiesNameAndParameters() {
        WebSocketExtension extension = WebSocketExtensionNegotiator.toSpringExtension(new WebSocketExtensionData(
                "permessage-deflate", Collections.singletonMap("server_max_window_bits", "12")));

        assertEquals("permessage-deflate", extension.getName());
        assertEquals("12", extension.getParameters().get("server_max_window_bits"));
    }

    @Test
    void splitOutsideQuotes_splitsOnlyOutsideQuotes() {
        assertEquals(List.of("a", "b"), WebSocketExtensionNegotiator.splitOutsideQuotes("a,b", ','));
        // 引号内的逗号不切分
        assertEquals(List.of("a", "\"b,c\""), WebSocketExtensionNegotiator.splitOutsideQuotes("a,\"b,c\"", ','));
        assertEquals(List.of(""), WebSocketExtensionNegotiator.splitOutsideQuotes("", ','));
    }
}
