package io.springperf.web.server;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;

/**
 * 响应写出层限制配置（对标 Spring Boot {@code server.max-swallow-size} 与
 * {@code server.max-http-response-header-size}）。启动期预解析一次，贯穿管线与响应对象。
 *
 * <ul>
 *   <li>{@code maxSwallowSize}：错误响应（4xx/5xx）后，单连接剩余请求 body 的丢弃上限（字节）。
 *       负数表示不限制；0 表示完全禁止在错误后保活（即任何带 body 的错误响应都关闭连接）。</li>
 *   <li>{@code maxResponseHeaderSize}：写出响应头总字节上限。超过则响应降级为最小 500，避免超长
 *       响应头污染连接（如业务误写海量 Cookie）。{@code <=0} 表示不限制。</li>
 * </ul>
 */
public class ResponseLimitConfig {

    /** 默认配置：swallow 2MB、响应头 8KB（与 Boot 默认值一致）。 */
    public static final ResponseLimitConfig DEFAULT =
            new ResponseLimitConfig(PropertiesConstant.MAX_SWALLOW_SIZE_DEFAULT,
                    PropertiesConstant.MAX_HTTP_RESPONSE_HEADER_SIZE_DEFAULT);

    private final long maxSwallowSize;
    private final int maxResponseHeaderSize;

    public ResponseLimitConfig(long maxSwallowSize, int maxResponseHeaderSize) {
        this.maxSwallowSize = maxSwallowSize;
        this.maxResponseHeaderSize = maxResponseHeaderSize;
    }

    public static ResponseLimitConfig fromProperties(ApplicationProperties props) {
        return new ResponseLimitConfig(
                props.getLong(PropertiesConstant.MAX_SWALLOW_SIZE),
                props.getInt(PropertiesConstant.MAX_HTTP_RESPONSE_HEADER_SIZE));
    }

    /** 错误响应后保活前允许丢弃的请求 body 上限（字节）；负数不限。 */
    public long getMaxSwallowSize() {
        return maxSwallowSize;
    }

    /** 响应头总字节上限；{@code <=0} 表示不限制。 */
    public int getMaxResponseHeaderSize() {
        return maxResponseHeaderSize;
    }

    /**
     * 是否需要在错误响应后关闭连接（而非保活）：请求 body 超过 swallow 上限时返回 {@code true}。
     * {@code maxSwallowSize < 0} 表示不限（永远保活）；{@code == 0} 表示只要错误响应带 body 就关闭。
     */
    public boolean shouldCloseAfterError(long requestContentLength) {
        if (maxSwallowSize < 0) {
            return false;
        }
        return requestContentLength > maxSwallowSize;
    }
}
