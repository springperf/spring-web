package io.springperf.web.websocket.server;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtension;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.WebSocketExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSocket 扩展（当前只有 {@code permessage-deflate}）的握手协商：解析请求头、协商参数、生成响应头值、对外描述。
 * <p>
 * 之所以不走 Netty 的 {@code WebSocketServerExtensionHandler}：它依赖 {@code WebSocketServerProtocolHandler} 抛出的
 * {@code HANDSHAKE_COMPLETE} 事件，而本框架自己完成握手。这里直接复用 Netty 的协商器，只负责「解析 → 写头 → 装编解码器」中的前三步。
 * </p>
 * <p>
 * 无状态、纯静态：协商只在握手时发生一次，不持有任何连接状态。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.8
 */
final class WebSocketExtensionNegotiator {

    /**
     * permessage-deflate 协商器（无状态，可跨连接共享）。默认参数：6 级压缩、接受客户端窗口位协商、不带 context takeover 偏好。
     */
    private static final PerMessageDeflateServerExtensionHandshaker PERMESSAGE_DEFLATE = new PerMessageDeflateServerExtensionHandshaker();

    private WebSocketExtensionNegotiator() {
    }

    /**
     * 从升级请求里协商 permessage-deflate。
     *
     * @return 协商成功的扩展；客户端未提或参数不可接受时为 null（不启用压缩）
     */
    @Nullable
    static WebSocketServerExtension negotiate(HttpHeaders requestHeaders) {
        for (String value : requestHeaders.getAll(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS)) {
            for (WebSocketExtensionData data : parseHeader(value)) {
                WebSocketServerExtension extension = PERMESSAGE_DEFLATE.handshakeExtension(data);
                if (extension != null) {
                    return extension;
                }
            }
        }
        return null;
    }

    /**
     * 把协商出的扩展转成 {@code Sec-WebSocket-Extensions} 的响应头值。
     * <p>
     * {@link WebSocketExtensionData} 没有重写 {@code toString()}（直接拼会得到 {@code Class@hash}），必须自己拼。
     * </p>
     */
    static String responseHeaderValue(WebSocketExtensionData data) {
        StringBuilder value = new StringBuilder(data.name());
        data.parameters().forEach((name, parameterValue) -> {
            value.append("; ").append(name);
            if (!parameterValue.isEmpty()) {
                value.append('=').append(parameterValue);
            }
        });
        return value.toString();
    }

    /** 协商结果 → Spring 的扩展描述，供 {@link org.springframework.web.socket.WebSocketSession#getExtensions()} 上报。 */
    static WebSocketExtension toSpringExtension(WebSocketExtensionData data) {
        return new WebSocketExtension(data.name(), data.parameters());
    }

    /**
     * 解析 {@code Sec-WebSocket-Extensions} 的一个头值，例如 {@code "permessage-deflate; client_max_window_bits, x-unknown"} → 两个
     * {@link WebSocketExtensionData}。
     * <p>
     * Netty 的解析工具类不在公开 API 里，这里按 RFC 6455 §9.1 的最小语法实现：逗号分条目、分号分参数、 无值参数（如 {@code client_max_window_bits}）记为空串。
     * </p>
     * <p>
     * 包级可见：解析规则（引号、空条目、{@code *_window_bits} 过滤）是纯函数，供同包测试直接断言，避免只经握手间接观察。
     * </p>
     */
    static List<WebSocketExtensionData> parseHeader(String value) {
        List<WebSocketExtensionData> result = new ArrayList<>(2);
        for (String entry : splitOutsideQuotes(value, ',')) {
            String trimmedEntry = entry.trim();
            if (trimmedEntry.isEmpty()) {
                continue;
            }
            List<String> tokens = splitOutsideQuotes(trimmedEntry, ';');
            Map<String, String> parameters = new HashMap<>(2);
            for (int i = 1; i < tokens.size(); i++) {
                String token = tokens.get(i).trim();
                if (token.isEmpty()) {
                    continue;
                }
                int eq = token.indexOf('=');
                if (eq < 0) {
                    // 无值参数：no_context_takeover 这类布尔开关必须保留（Netty 用 containsKey 判断）；
                    // 但 *_window_bits 是数值参数，浏览器常发的 `client_max_window_bits`（无值）会让 Netty 的
                    // 协商器 parseInt("") 直接抛异常。忽略它等价于「不回该参数」，对端按 15 位默认窗口处理，协议合法。
                    if (!token.endsWith("_window_bits")) {
                        parameters.put(token, "");
                    }
                } else {
                    parameters.put(token.substring(0, eq).trim(), unquote(token.substring(eq + 1).trim()));
                }
            }
            result.add(new WebSocketExtensionData(tokens.get(0).trim(), parameters));
        }
        return result;
    }

    /**
     * 按分隔符切分但不切引号内的分隔符。
     * <p>
     * RFC 6455 §9.1 的扩展参数可以是 {@code quoted-string}，值里允许出现逗号与分号（如 {@code x="a,b"}）； 直接 {@code String.split}
     * 会把这类值切坏。这里只处理引号成对出现的最小场景，不处理 {@code \-} 转义（WebSocket 扩展参数 实际用到的都是 token 或数字）。
     * </p>
     */
    static List<String> splitOutsideQuotes(String value, char delimiter) {
        List<String> parts = new ArrayList<>(2);
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == delimiter && !quoted) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
