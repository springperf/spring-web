package io.springperf.web.server;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.util.DataSizeUtils;

/**
 * multipart 上传配置（对齐 Spring Boot {@code spring.servlet.multipart.*}），启动期预解析一次。
 *
 * <ul>
 *   <li>{@code enabled}：是否启用 multipart 解析（默认 true）</li>
 *   <li>{@code maxFileSize}：单文件大小上限（字节；{@code <=0} 表示不限制）</li>
 *   <li>{@code maxRequestSize}：整个 multipart 请求体上限（字节；{@code <=0} 表示不限制）</li>
 * </ul>
 */
public final class MultipartConfig {

    /** 未显式配置 file-size-threshold 时使用的框架默认值（Netty MINSIZE=16KB，保持既有行为）。 */
    public static final long FILE_SIZE_THRESHOLD_UNSET = -1L;

    /** 默认配置：启用、两个上限均不限制、阈值与目录沿用框架既有行为。 */
    public static final MultipartConfig DEFAULT =
            new MultipartConfig(true, -1L, -1L, FILE_SIZE_THRESHOLD_UNSET, "");

    private final boolean enabled;
    private final long maxFileSize;
    private final long maxRequestSize;
    /** part 落盘阈值（字节）；{@code <0} 表示未配置（沿用 Netty MINSIZE）。 */
    private final long fileSizeThreshold;
    /** 上传临时目录；空表示沿用 Netty 默认（java.io.tmpdir）。 */
    private final String location;

    public MultipartConfig(boolean enabled, long maxFileSize, long maxRequestSize) {
        this(enabled, maxFileSize, maxRequestSize, FILE_SIZE_THRESHOLD_UNSET, "");
    }

    public MultipartConfig(boolean enabled, long maxFileSize, long maxRequestSize,
                           long fileSizeThreshold, String location) {
        this.enabled = enabled;
        this.maxFileSize = maxFileSize;
        this.maxRequestSize = maxRequestSize;
        this.fileSizeThreshold = fileSizeThreshold;
        this.location = location != null ? location : "";
    }

    /**
     * 从配置解析。{@code spring.servlet.multipart.*} 为 -1（默认）时：
     * <ul>
     *   <li>{@code max-file-size}：不限</li>
     *   <li>{@code max-request-size}：回退 {@code server.http.max-content-length}（保持既有整体上限语义）</li>
     *   <li>{@code file-size-threshold}：沿用框架默认（Netty MINSIZE=16KB）</li>
     *   <li>{@code location}：沿用 Netty 默认（java.io.tmpdir）</li>
     * </ul>
     * 用户显式配置时以显式值为准。
     */
    public static MultipartConfig fromProperties(ApplicationProperties props) {
        boolean enabled = props.getBoolean(PropertiesConstant.SPRING_SERVLET_MULTIPART_ENABLED,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_ENABLED_DEFAULT);
        long maxFileSize = parseSize(props,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE_DEFAULT, -1L);
        long maxRequestSize = parseSize(props,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE_DEFAULT, -1L);
        if (maxRequestSize <= 0) {
            // 未显式配置时，沿用既有的 server.http.max-content-length 作为整体上限
            maxRequestSize = props.getLong(PropertiesConstant.HTTP_MAX_CONTENT_LENGTH);
        }
        // file-size-threshold：未配置（-1/空）时保留 UNSET 语义，由 resolver 决定框架默认
        long threshold = parseSize(props,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_FILE_SIZE_THRESHOLD,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_FILE_SIZE_THRESHOLD_DEFAULT,
                FILE_SIZE_THRESHOLD_UNSET);
        if (threshold == 0) {
            // 显式 0：对齐 Boot 的“全部落盘”语义，映射为 Netty 的阈值 0
            threshold = 0;
        }
        String location = props.get(PropertiesConstant.SPRING_SERVLET_MULTIPART_LOCATION,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_LOCATION_DEFAULT);
        return new MultipartConfig(enabled, maxFileSize, maxRequestSize, threshold, location);
    }

    /** 解析 DataSize 风格字节串；{@code -1} / 空 / 非法值回退 defaultBytes。 */
    private static long parseSize(ApplicationProperties props, String key, String defaultValue, long fallback) {
        String raw = props.get(key, defaultValue);
        if (raw == null || raw.trim().isEmpty() || "-1".equals(raw.trim())) {
            return fallback;
        }
        return DataSizeUtils.parseBytesOrDefault(raw, fallback);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 单文件大小上限（字节，{@code <=0} 不限）。 */
    public long getMaxFileSize() {
        return maxFileSize;
    }

    /** 整个 multipart 请求体上限（字节，{@code <=0} 不限）。 */
    public long getMaxRequestSize() {
        return maxRequestSize;
    }

    /** part 落盘阈值（字节）；{@code <0} 表示未配置（沿用框架默认 Netty MINSIZE）。 */
    public long getFileSizeThreshold() {
        return fileSizeThreshold;
    }

    /** 上传临时目录；空表示沿用 Netty 默认（java.io.tmpdir）。 */
    public String getLocation() {
        return location;
    }
}
