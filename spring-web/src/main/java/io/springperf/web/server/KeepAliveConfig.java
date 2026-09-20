package io.springperf.web.server;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;

/**
 * 对齐 Spring Boot {@code server.keep-alive-timeout} / {@code server.max-keep-alive-requests} 的不可变配置。
 *
 * <ul>
 *   <li>{@code timeoutMillis <= 0}：不启用 keep-alive 空闲超时（沿用 TCP SO_KEEPALIVE）。</li>
 *   <li>{@code maxRequests <= 0}：不限制单连接请求数。</li>
 * </ul>
 *
 * 默认（{@link #fromProperties}）：空闲超时禁用、单连接请求上限 = 100（对齐 Tomcat 默认）。
 * {@link #DISABLED} 用于构造链的中间默认（完全不注入 handler）。
 */
public final class KeepAliveConfig {

    /** 完全禁用（不注入 handler）。 */
    public static final KeepAliveConfig DISABLED = new KeepAliveConfig(0L, 0);

    private final long timeoutMillis;
    private final int maxRequests;

    public KeepAliveConfig(long timeoutMillis, int maxRequests) {
        this.timeoutMillis = timeoutMillis;
        this.maxRequests = maxRequests;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    /** 是否任一维度启用：决定是否向 pipeline 注入 {@link KeepAliveHandler}。 */
    public boolean isEnabled() {
        return timeoutMillis > 0 || maxRequests > 0;
    }

    public static KeepAliveConfig fromProperties(ApplicationProperties props) {
        long timeoutMillis = props.getDurationMillis(
                PropertiesConstant.KEEP_ALIVE_TIMEOUT, PropertiesConstant.KEEP_ALIVE_TIMEOUT_DEFAULT);
        int maxRequests = props.getInt(PropertiesConstant.MAX_KEEP_ALIVE_REQUESTS);
        return new KeepAliveConfig(timeoutMillis, maxRequests);
    }
}
