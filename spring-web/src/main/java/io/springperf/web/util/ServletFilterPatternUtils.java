package io.springperf.web.util;

import io.springperf.web.util.support.ContainmentResult;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Servlet 规范的 URL 路径模式匹配工具。
 *
 * <p>与 {@link PathPatternUtils}（Ant/Spring 风格）不同，此类实现 Servlet 容器路径匹配语义：
 * <ul>
 *   <li>{@code /*} — 匹配所有路径（Servlet 中相当于 Ant 的 {@code /**}）</li>
 *   <li>{@code /prefix/*} — 前缀递归匹配（如 {@code /api/*} 匹配 {@code /api/foo}、{@code /api/foo/bar}）</li>
 *   <li>{@code *.suffix} — 后缀匹配（如 {@code *.json} 匹配任意以 {@code .json} 结尾的路径）</li>
 *   <li>精确路径 — 完全匹配</li>
 * </ul>
 *
 * <p>复用了三段式编译期推断思想（{@link ContainmentResult#ALWAYS}/{@link ContainmentResult#NEVER}/{@link ContainmentResult#RUNTIME}），
 * 但内部使用 Servlet 规则进行判断。
 *
 * <p>注意：{@code matchPathRuleToCached} 接受的 {@code pathRule} 来自 Handler 映射（Ant 风格），
 * 可能包含 {@code {var}}、{@code **} 等 Ant 通配符。此类对 Ant 通配符做保守处理：
 * 能确定包含时返回 ALWAYS、能确定不包含时返回 NEVER、否则返回 RUNTIME。
 *
 * <p>专用于 {@code WebFilter} 路径匹配，不适用于 {@code HandlerInterceptor}（后者应继续使用 {@link PathPatternUtils}）。
 */
public class ServletFilterPatternUtils {

    // ========== Pattern type detection ==========

    /**
     * 是否为 ALL 模式：{@code /*} 或 {@code /}。
     * Servlet 中 {@code /*} 匹配所有路径。
     */
    public static boolean isAllPattern(String pattern) {
        return "/*".equals(pattern) || "/".equals(pattern);
    }

    /**
     * 是否为前缀模式：以 {@code /} 开头、以 {@code /*} 结尾、长度 &gt; 2。
     * 如 {@code /api/*}、{@code /api/users/*}。
     */
    public static boolean isPrefixPattern(String pattern) {
        return pattern.startsWith("/") && pattern.endsWith("/*") && pattern.length() > 2;
    }

    /**
     * 是否为后缀模式：以 {@code *.} 开头。
     * 如 {@code *.json}、{@code *.do}。
     */
    public static boolean isSuffixPattern(String pattern) {
        return pattern.startsWith("*.");
    }

    /**
     * 校验 Servlet URL 模式的合法性，不合法时返回描述信息，合法返回 {@code null}。
     * <p>Servlet 规范要求 URL 模式必须以 {@code /} 或 {@code *.} 开头。
     * 不合规的模式（如 {@code api/test/*}）会被视为精确字面量，实际不会匹配任何请求。</p>
     */
    @Nullable
    public static String validateServletPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return "pattern 为空";
        }
        // 合法格式
        if (isAllPattern(pattern) || isPrefixPattern(pattern) || isSuffixPattern(pattern)) {
            return null;
        }
        // / 开头且不含 Servlet/ Ant 通配符 → 精确路径，合法
        if (pattern.startsWith("/") && !hasAntWildcard(pattern)) {
            return null;
        }
        // 含通配符但不以 / 或 *. 开头 → 不合法
        if (hasAntWildcard(pattern)) {
            return "Pattern '" + pattern + "' 不符合 Servlet URL 模式规范，应以 / 或 *. 开头。当前将被当作精确字面量处理，不会匹配任何请求路径";
        }
        // 不含通配符也不以 / 开头 → 不合法
        return "Pattern '" + pattern + "' 缺少前导 /，不符合 Servlet URL 模式规范";
    }

    // ========== Pattern extraction ==========

    /**
     * 从前缀模式中提取前缀路径。
     * 如 {@code /api/*} → {@code /api}。
     */
    public static String extractPrefix(String prefixPattern) {
        return prefixPattern.substring(0, prefixPattern.length() - 2);
    }

    /**
     * 从后缀模式中提取后缀。
     * 如 {@code *.json} → {@code .json}。
     */
    public static String extractSuffix(String suffixPattern) {
        return suffixPattern.substring(1);
    }

    // ========== Runtime matching ==========

    /**
     * Servlet 规范的运行时路径匹配。
     *
     * @param pattern    Servlet URL 模式（来自 Filter 配置：{@code /*}、{@code /api/*}、{@code *.json} 或精确路径）
     * @param lookupPath 实际请求路径
     * @return 是否匹配
     */
    public static boolean matches(String pattern, String lookupPath) {
        if (isAllPattern(pattern)) {
            return true;
        }
        if (isPrefixPattern(pattern)) {
            String prefix = extractPrefix(pattern);
            return lookupPath.equals(prefix) || lookupPath.startsWith(prefix + "/");
        }
        if (isSuffixPattern(pattern)) {
            return lookupPath.endsWith(extractSuffix(pattern));
        }
        return lookupPath.equals(pattern);
    }

    // ========== Three-mode compile-time inference ==========

    /**
     * 三段式编译期推断：根据 mapping 的 pathRule（Ant 风格）判断 filter 是否适用。
     * <p>逻辑与 {@link PathPatternUtils#matchPathRuleToCached} 一致，但使用 Servlet 模式规则。</p>
     */
    public static ContainmentResult matchPathRuleToCached(
            List<String> includePatterns, List<String> excludePatterns, String pathRule) {

        if (includePatterns.isEmpty() && excludePatterns.isEmpty()) {
            return ContainmentResult.ALWAYS;
        }
        ContainmentResult includeResult = patternListContains(includePatterns, pathRule);
        if (includePatterns.isEmpty()) {
            includeResult = ContainmentResult.ALWAYS;
        }
        if (includeResult == ContainmentResult.NEVER) {
            return ContainmentResult.NEVER;
        }

        ContainmentResult excludeResult = patternListContains(excludePatterns, pathRule);
        if (excludeResult == ContainmentResult.ALWAYS) {
            return ContainmentResult.NEVER;
        }
        if (includeResult == ContainmentResult.ALWAYS
                && excludeResult == ContainmentResult.NEVER) {
            if (excludePatterns.isEmpty()) {
                return ContainmentResult.ALWAYS;
            }
            boolean disjoint = excludePatterns.stream()
                    .allMatch(e -> patternsDisjoint(e, pathRule));
            if (disjoint) {
                return ContainmentResult.ALWAYS;
            }
            return ContainmentResult.RUNTIME;
        }
        return ContainmentResult.RUNTIME;
    }

    /**
     * 判断一个模式列表是否确定包含指定 pathRule。
     */
    public static ContainmentResult patternListContains(
            List<String> patternList, String pathRule) {
        boolean sawRuntime = false;
        for (String inc : patternList) {
            ContainmentResult r = patternContains(inc, pathRule);
            if (r == ContainmentResult.ALWAYS) {
                return ContainmentResult.ALWAYS;
            }
            if (r == ContainmentResult.RUNTIME) {
                sawRuntime = true;
            }
        }
        return sawRuntime ? ContainmentResult.RUNTIME : ContainmentResult.NEVER;
    }

    /**
     * 判断容器模式（Servlet 风格）是否确定包含被包含模式（handler pathRule，可能含 Ant 通配符）匹配的所有请求。
     */
    public static ContainmentResult patternContains(String container, String containee) {
        if (isAllPattern(container)) {
            return ContainmentResult.ALWAYS;
        }
        if (isPrefixPattern(container)) {
            return prefixContains(extractPrefix(container), containee);
        }
        if (isSuffixPattern(container)) {
            return suffixContains(extractSuffix(container), containee);
        }
        return exactContains(container, containee);
    }

    /**
     * 判断两个模式是否确定不相交。处理 Servlet 模式 vs Servlet 模式、Servlet 模式 vs Ant 通配符模式。
     */
    public static boolean patternsDisjoint(String p1, String p2) {
        // ALL (/*) 与任何模式都有交集
        if (isAllPattern(p1) || isAllPattern(p2)) {
            return false;
        }

        boolean p1Prefix = isPrefixPattern(p1);
        boolean p2Prefix = isPrefixPattern(p2);
        boolean p1Suffix = isSuffixPattern(p1);
        boolean p2Suffix = isSuffixPattern(p2);
        boolean p1Exact = !p1Prefix && !p1Suffix;
        boolean p2Exact = !p2Prefix && !p2Suffix;

        // PREFIX vs PREFIX：前缀不相交则整体不相交
        if (p1Prefix && p2Prefix) {
            String pre1 = extractPrefix(p1) + "/";
            String pre2 = extractPrefix(p2) + "/";
            return !pre1.equals(pre2)
                    && !pre2.startsWith(pre1)
                    && !pre1.startsWith(pre2);
        }

        // PREFIX vs EXACT：检查"EXACT"的首段字面量是否与 prefix 不同
        if (p1Prefix && p2Exact) {
            if (hasAntWildcard(p2)) {
                return isPrefixDisjointFromExactWithWildcard(extractPrefix(p1), p2);
            }
            return !isExactUnderPrefix(extractPrefix(p1), p2);
        }
        if (p2Prefix && p1Exact) {
            if (hasAntWildcard(p1)) {
                return isPrefixDisjointFromExactWithWildcard(extractPrefix(p2), p1);
            }
            return !isExactUnderPrefix(extractPrefix(p2), p1);
        }

        // PREFIX vs SUFFIX — 可能相交（如 /api/* 和 *.json → /api/data.json）
        if ((p1Prefix && p2Suffix) || (p1Suffix && p2Prefix)) {
            return false;
        }

        // SUFFIX vs SUFFIX
        if (p1Suffix && p2Suffix) {
            String s1 = extractSuffix(p1);
            String s2 = extractSuffix(p2);
            return !s1.endsWith(s2) && !s2.endsWith(s1);
        }

        // SUFFIX vs 含 Ant 通配符的 EXACT：保守处理，不可证明不相交
        if (p1Suffix && p2Exact) {
            if (hasAntWildcard(p2)) {
                return false;
            }
            return !p2.endsWith(extractSuffix(p1));
        }
        if (p2Suffix && p1Exact) {
            if (hasAntWildcard(p1)) {
                return false;
            }
            return !p1.endsWith(extractSuffix(p2));
        }

        // EXACT vs EXACT：检查首段字面量是否不同
        if (hasAntWildcard(p1) || hasAntWildcard(p2)) {
            return isExactDisjointWithWildcard(p1, p2);
        }
        return !p1.equals(p2);
    }

    // ========== Internal helpers ==========

    /**
     * 判断模式是否含有 Ant 通配符（{@code {var}}、{@code **}、{@code *}、{@code ?}）。
     * 编译期推断中，含通配符的 containee 需要保守处理。
     */
    private static boolean hasAntWildcard(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('{') >= 0 || s.indexOf('?') >= 0;
    }

    /**
     * 提取路径模式中第一个固定路径段。
     * 如 {@code /api/{id}} → {@code /api}，{@code /other/**} → {@code /other}，
     * {@code {var}/foo} → {@code null}（首段非字面量）。
     */
    private static String extractFirstLiteralSegment(String path) {
        if (!path.startsWith("/")) {
            return null;
        }
        int end = path.indexOf('/', 1);
        if (end < 0) {
            end = path.length();
        }
        String first = path.substring(0, end);
        if (hasAntWildcard(first)) {
            return null;
        }
        return first;
    }

    /**
     * 前缀模式（如 /api/*）是否确定包含 containee（handler pathRule）。
     *
     * <p>对于 Ant 通配符 containee：若其首段字面量不同则 NEVER，若字面量能落在前缀下则检查字面前缀关系。
     */
    private static ContainmentResult prefixContains(String prefix, String containee) {
        // ALL (/*) 作为 containee → 前缀不能确定包含所有路径
        if (isAllPattern(containee)) {
            return ContainmentResult.RUNTIME;
        }

        // Servlet 前缀作为 containee → 检查子前缀关系
        if (isPrefixPattern(containee)) {
            String cp = extractPrefix(containee);
            if (cp.equals(prefix) || cp.startsWith(prefix + "/")) {
                return ContainmentResult.ALWAYS;
            }
            return ContainmentResult.NEVER;
        }

        // Servlet 后缀作为 containee → 无法确定
        if (isSuffixPattern(containee)) {
            return ContainmentResult.RUNTIME;
        }

        // containee 字面量以 prefix/ 开头 → 所有匹配请求都在前缀下
        if (containee.startsWith(prefix + "/")) {
            return ContainmentResult.ALWAYS;
        }

        // containee 恰好等于 prefix → 运行时匹配
        if (containee.equals(prefix)) {
            return ContainmentResult.ALWAYS;
        }

        // 含 Ant 通配符的 containee：检查首段字面量是否与 prefix 不同
        if (hasAntWildcard(containee)) {
            String firstLit = extractFirstLiteralSegment(containee);
            if (firstLit != null && !firstLit.equals(prefix)
                    && !firstLit.startsWith(prefix + "/")) {
                // 首段字面量不同且不落在 prefix 下 → NEVER
                // 如 prefix=/api, containee=/other/** → /other/foo 不在 /api/ 下
                return ContainmentResult.NEVER;
            }
            return ContainmentResult.RUNTIME;
        }

        // 不含通配符且不以 prefix/ 开头 → 不同路径
        return ContainmentResult.NEVER;
    }

    /**
     * 后缀模式（如 *.json）是否确定包含 containee。
     */
    private static ContainmentResult suffixContains(String suffix, String containee) {
        if (isAllPattern(containee) || isPrefixPattern(containee)) {
            return ContainmentResult.RUNTIME;
        }
        if (isSuffixPattern(containee)) {
            String cs = extractSuffix(containee);
            return cs.endsWith(suffix)
                    ? ContainmentResult.ALWAYS
                    : ContainmentResult.NEVER;
        }
        // 含 Ant 通配符 → 保守
        if (hasAntWildcard(containee)) {
            return ContainmentResult.RUNTIME;
        }
        // 精确路径
        return containee.endsWith(suffix)
                ? ContainmentResult.ALWAYS
                : ContainmentResult.NEVER;
    }

    /**
     * 精确路径是否确定包含 containee。
     */
    private static ContainmentResult exactContains(String exact, String containee) {
        if (hasAntWildcard(containee) || isAllPattern(containee)
                || isPrefixPattern(containee) || isSuffixPattern(containee)) {
            return ContainmentResult.RUNTIME;
        }
        return exact.equals(containee) ? ContainmentResult.ALWAYS : ContainmentResult.NEVER;
    }

    private static boolean isExactUnderPrefix(String prefix, String exactPath) {
        return exactPath.equals(prefix) || exactPath.startsWith(prefix + "/");
    }

    /**
     * PREFIX 模式与含 Ant 通配符的 EXACT 模式的不相交判断。
     * 如果 EXACT 模式的首段是固定字面量且不落在 prefix 下 → 确定不相交。
     */
    private static boolean isPrefixDisjointFromExactWithWildcard(String prefix, String exactWithWildcard) {
        String firstLit = extractFirstLiteralSegment(exactWithWildcard);
        if (firstLit == null) {
            return false; // 首段是通配符，无法判断
        }
        // 首段字面量与 prefix 不同且不在 prefix 下 → 不相交
        return !firstLit.equals(prefix) && !firstLit.startsWith(prefix + "/");
    }

    /**
     * 两个可能含 Ant 通配符的"EXACT"模式的不相交判断。
     * 如果两者首段都是不同的固定字面量 → 确定不相交。
     */
    private static boolean isExactDisjointWithWildcard(String p1, String p2) {
        String firstLit1 = extractFirstLiteralSegment(p1);
        String firstLit2 = extractFirstLiteralSegment(p2);
        if (firstLit1 == null || firstLit2 == null) {
            return false; // 任一首段是通配符，无法判断
        }
        return !firstLit1.equals(firstLit2);
    }
}
