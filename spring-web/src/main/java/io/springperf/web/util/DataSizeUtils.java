package io.springperf.web.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring {@code DataSize} 风格的大小解析（启动期使用，便于 fail-fast）。
 *
 * <p>支持：纯数字（按字节）、可选小数、以及 {@code B/KB/MB/GB/TB}（1024 进制，对齐 Spring Boot）
 * 与简写 {@code K/M/G/T}。示例：{@code 1024}、{@code 10MB}、{@code 1.5GB}、{@code 2k}。</p>
 */
public final class DataSizeUtils {

    private DataSizeUtils() {
    }

    /** 匹配纯数字（可含小数） + 可选单位字母。 */
    private static final Pattern SIZE_PATTERN =
            Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]*)$");

    /**
     * 解析为字节数。
     *
     * @param raw   原始配置值（如 {@code 10MB}）
     * @param key   配置键名（仅用于异常信息定位）
     * @throws IllegalArgumentException 格式非法或单位未知（启动期 fail-fast）
     */
    public static long parseBytes(String raw, String key) {
        if (raw == null) {
            throw new IllegalArgumentException("Invalid " + key + ": null");
        }
        String s = raw.trim();
        Matcher m = SIZE_PATTERN.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid " + key + ": " + raw);
        }
        double value = Double.parseDouble(m.group(1));
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        long multiplier;
        switch (unit) {
            case "":
            case "b":
            case "bytes":
                multiplier = 1L; break;
            case "k":
            case "kb":
                multiplier = 1024L; break;
            case "m":
            case "mb":
                multiplier = 1024L * 1024; break;
            case "g":
            case "gb":
                multiplier = 1024L * 1024 * 1024; break;
            case "t":
            case "tb":
                multiplier = 1024L * 1024 * 1024 * 1024; break;
            default:
                throw new IllegalArgumentException("Unknown size unit in " + key + ": " + raw);
        }
        return (long) (value * multiplier);
    }

    /**
     * 宽解析：无法解析时返回 {@code defaultBytes} 而非抛异常。
     * 适合「用户可选配置、非法值不应中断启动」的场景。
     */
    public static long parseBytesOrDefault(String raw, long defaultBytes) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultBytes;
        }
        try {
            return parseBytes(raw, "value");
        } catch (IllegalArgumentException e) {
            return defaultBytes;
        }
    }
}
