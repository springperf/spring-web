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
