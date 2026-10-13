package io.springperf.web.server;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.util.DataSizeUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 不可变压缩配置，对齐 Spring Boot {@code server.compression.*}。
 * 由 {@link #fromProperties(ApplicationProperties)} 在启动期预解析（配置错误在此 fail-fast）。
 */
public final class CompressionConfig {

    /** 关闭态单例：管线不注入压缩器，零运行时开销。 */
    public static final CompressionConfig DISABLED = new CompressionConfig(false,
            Collections.emptySet(), Collections.emptyList(), 0L,
            PropertiesConstant.SERVER_COMPRESSION_LEVEL_DEFAULT);

    private final boolean enabled;
    private final Set<String> mimeTypes;
    private final List<Pattern> excludedUserAgents;
    private final long minResponseSizeBytes;
    private final int level;

    private CompressionConfig(boolean enabled, Set<String> mimeTypes,
                              List<Pattern> excludedUserAgents, long minResponseSizeBytes, int level) {
        this.enabled = enabled;
        this.mimeTypes = mimeTypes;
        this.excludedUserAgents = excludedUserAgents;
        this.minResponseSizeBytes = minResponseSizeBytes;
        this.level = level;
    }

    public static CompressionConfig fromProperties(ApplicationProperties props) {
        boolean enabled = props.getBoolean(PropertiesConstant.SERVER_COMPRESSION_ENABLED,
                PropertiesConstant.SERVER_COMPRESSION_ENABLED_DEFAULT);
        if (!enabled) {
            return DISABLED;
        }
        // props.get 声明为 @Nullable：显式回落到各自的非空默认值（这里默认值非空，兜底仅为契约完整）
        // 注：master 用 JDK9+ 的 Objects.requireNonNullElse，本分支（JDK8）以等价的显式判空实现
        String mimeTypesRaw = props.get(PropertiesConstant.SERVER_COMPRESSION_MIME_TYPES,
                PropertiesConstant.SERVER_COMPRESSION_MIME_TYPES_DEFAULT);
        if (mimeTypesRaw == null) {
            mimeTypesRaw = PropertiesConstant.SERVER_COMPRESSION_MIME_TYPES_DEFAULT;
        }
        Set<String> mimeTypes = Arrays.stream(mimeTypesRaw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        String excludedUserAgentsRaw = props.get(PropertiesConstant.SERVER_COMPRESSION_EXCLUDED_USER_AGENTS, "");
        if (excludedUserAgentsRaw == null) {
            excludedUserAgentsRaw = "";
        }
        List<Pattern> excludedUserAgents = Arrays.stream(excludedUserAgentsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> Pattern.compile(s, Pattern.CASE_INSENSITIVE))
                .collect(Collectors.toList());
        long minResponseSizeBytes = parseSizeBytes(props.get(
                PropertiesConstant.SERVER_COMPRESSION_MIN_RESPONSE_SIZE,
                PropertiesConstant.SERVER_COMPRESSION_MIN_RESPONSE_SIZE_DEFAULT));
        return new CompressionConfig(true, mimeTypes, excludedUserAgents, minResponseSizeBytes,
                PropertiesConstant.SERVER_COMPRESSION_LEVEL_DEFAULT);
    }

    /**
     * 解析 Spring DataSize 风格的大小（复用 {@link DataSizeUtils}）：纯数字按字节，
     * 后缀 B/KB/MB/GB（1024 进制，对齐 Spring）。解析失败以 {@link IllegalStateException} 中断
     * （启动期 fail-fast）。
     */
    private static long parseSizeBytes(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            s = PropertiesConstant.SERVER_COMPRESSION_MIN_RESPONSE_SIZE_DEFAULT;
        }
        try {
            return DataSizeUtils.parseBytes(s, PropertiesConstant.SERVER_COMPRESSION_MIN_RESPONSE_SIZE);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Set<String> getMimeTypes() {
        return java.util.Collections.unmodifiableSet(mimeTypes);
    }

    public List<Pattern> getExcludedUserAgents() {
        return java.util.Collections.unmodifiableList(excludedUserAgents);
    }

    public long getMinResponseSizeBytes() {
        return minResponseSizeBytes;
    }

    public int getLevel() {
        return level;
    }
}
