package io.springperf.web.util;

import io.springperf.web.util.support.ContainmentResult;
import io.springperf.web.util.support.SegKind;
import io.springperf.web.util.support.Segment;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.util.RouteMatcher;
import org.springframework.util.SimpleRouteMatcher;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PathPatternRouteMatcher;
import org.springframework.web.util.pattern.PatternParseException;

import java.util.List;

public class PathPatternUtils {

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final RouteMatcher PATTERN_ROUTE_MATCHER = new PathPatternRouteMatcher(PARSER);

    private static final PathMatcher MATCHER = new AntPathMatcher();

    private static final RouteMatcher PATH_ROUTE_MATCHER = new SimpleRouteMatcher(MATCHER);

    public static PathPatternParser getParser() {
        return PARSER;
    }

    public static PathMatcher getMatcher() {
        return MATCHER;
    }

    public static RouteMatcher getPatternRouteMatcher() {
        return PATTERN_ROUTE_MATCHER;
    }

    public static RouteMatcher getPathRouteMatcher() {
        return PATH_ROUTE_MATCHER;
    }

    public static boolean supportPatternParse(String patternString) {
        try {
            return PARSER.parse(patternString) != null;
        } catch (PatternParseException ex) {
            return false;
        }
    }

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
        if (sawRuntime) {
            return ContainmentResult.RUNTIME;
        }
        // 所有 include 都 NEVER
        return ContainmentResult.NEVER;
    }

    public static ContainmentResult patternContains(String containerStr, String containeeStr) {
        List<Segment> container = Segment.parse(containerStr);
        List<Segment> containee = Segment.parse(containeeStr);
        return match(container, 0, containee, 0);
    }

    public static boolean pathHaveWildcard(String path) {
        return path.contains("*") || path.contains("?") || path.contains("{") || path.contains("}");
    }

    /**
     * 判断两个路径模式是否确定不相交（没有请求路径能同时匹配二者）。
     * <p>仅在可编译期证明不相交时返回 {@code true}，否则保守返回 {@code false}。</p>
     */
    /**
     * 三段式编译期推断：根据 mapping 的 pathRule 判断此 registration 是否适用。
     * <p>对应 {@link ContainmentResult}：ALWAYS — 确定包含；NEVER — 确定不包含；RUNTIME — 需运行时判断。</p>
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

    public static boolean patternsDisjoint(String p1, String p2) {
        List<Segment> s1 = Segment.parse(p1);
        List<Segment> s2 = Segment.parse(p2);
        return segmentsDisjoint(s1, 0, s2, 0);
    }

    private static boolean segmentsDisjoint(List<Segment> s1, int i, List<Segment> s2, int j) {
        while (i < s1.size() && j < s2.size()) {
            Segment a = s1.get(i);
            Segment b = s2.get(j);

            // MULTI_WILDCARD 可匹配任意段 → 无法证明不相交
            if (a.kind == SegKind.MULTI_WILDCARD || b.kind == SegKind.MULTI_WILDCARD) {
                return false;
            }

            // 同位置都是 LITERAL：值不同则确定不相交
            if (a.kind == SegKind.LITERAL && b.kind == SegKind.LITERAL) {
                if (!a.literal.equals(b.literal)) {
                    return true;
                }
                i++;
                j++;
                continue;
            }

            // LITERAL vs REGEX：字面量不匹配正则则不相交
            if (a.kind == SegKind.LITERAL && b.kind == SegKind.REGEX) {
                return !b.regex.matcher(a.literal).matches();
            }
            if (a.kind == SegKind.REGEX && b.kind == SegKind.LITERAL) {
                return !a.regex.matcher(b.literal).matches();
            }

            // 其他组合（含通配符）→ 可能相交
            return false;
        }

        // 剩余段检查：只有 LITERAL 则不同路径深度必然不相交
        while (i < s1.size()) {
            if (s1.get(i++).kind != SegKind.LITERAL) return false;
        }
        while (j < s2.size()) {
            if (s2.get(j++).kind != SegKind.LITERAL) return false;
        }
        return true;
    }

    private static ContainmentResult match(
            List<Segment> c, int i,
            List<Segment> e, int j) {

        ContainmentResult acc = ContainmentResult.ALWAYS;

        while (i < c.size() && j < e.size()) {
            Segment a = c.get(i);
            Segment b = e.get(j);

            if (a.kind == SegKind.MULTI_WILDCARD) {
                return matchMulti(c, i, e, j);
            }

            ContainmentResult r = segmentContains(a, b);
            if (r == ContainmentResult.NEVER) {
                return ContainmentResult.NEVER;
            }

            acc = ContainmentResult.and(acc, r);
            i++;
            j++;
        }

        // containee 剩余，container 已用完
        if (j < e.size()) {
            return ContainmentResult.NEVER;
        }

        // container 剩余
        while (i < c.size()) {
            Segment s = c.get(i++);
            if (s.kind == SegKind.MULTI_WILDCARD) {
                continue;
            }
            return ContainmentResult.NEVER;
        }

        return acc;
    }

    private static ContainmentResult matchMulti(
            List<Segment> c, int i,
            List<Segment> e, int j) {

        ContainmentResult acc = ContainmentResult.NEVER;

        // ** 匹配 0..n 个 segment
        for (int k = j; k <= e.size(); k++) {
            ContainmentResult r = match(c, i + 1, e, k);
            if (r == ContainmentResult.ALWAYS) {
                return ContainmentResult.ALWAYS;
            }
            if (r == ContainmentResult.RUNTIME) {
                acc = ContainmentResult.RUNTIME;
            }
        }
        return acc;
    }

    private static ContainmentResult segmentContains(
            Segment a, Segment b) {
        switch (a.kind) {
            case LITERAL:
                if (b.kind == SegKind.LITERAL) {
                    return a.literal.equals(b.literal)
                            ? ContainmentResult.ALWAYS
                            : ContainmentResult.NEVER;
                }
                return ContainmentResult.NEVER;

            case SINGLE_WILDCARD:
                if (b.kind == SegKind.MULTI_WILDCARD) {
                    return ContainmentResult.RUNTIME;
                }
                return ContainmentResult.ALWAYS;

            case REGEX:
                if (b.kind == SegKind.LITERAL) {
                    return a.regex.matcher(b.literal).matches()
                            ? ContainmentResult.ALWAYS
                            : ContainmentResult.NEVER;
                }
                return ContainmentResult.RUNTIME;

            default:
                return ContainmentResult.RUNTIME;
        }
    }


    }
