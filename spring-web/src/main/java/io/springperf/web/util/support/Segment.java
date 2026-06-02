package io.springperf.web.util.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class Segment {

    public static final Segment SINGLE_WILDCARD = new Segment(SegKind.SINGLE_WILDCARD, "*", null);

    public static final Segment MULTI_WILDCARD = new Segment(SegKind.MULTI_WILDCARD, "**", null);

    public final SegKind kind;
    public final String literal;   // LITERAL
    public final Pattern regex;    // REGEX

    private Segment(SegKind kind, String literal, Pattern regex) {
        this.kind = kind;
        this.literal = literal;
        this.regex = regex;
    }

    public static Segment literal(String s) {
        return new Segment(SegKind.LITERAL, s, null);
    }

    public static Segment regex(String s, Pattern pattern) {
        return new Segment(SegKind.REGEX, s, pattern);
    }

    public static List<Segment> parse(String path) {
        if ("/".equals(path)) {
            return new ArrayList<>();
        }

        String[] parts = path.split("/");
        List<Segment> segments = new ArrayList<>();

        for (String p : parts) {
            if (p.isEmpty()) continue;

            if ("**".equals(p)) {
                segments.add(MULTI_WILDCARD);
            } else if ("*".equals(p) || isSimpleVar(p)) {
                segments.add(SINGLE_WILDCARD);
            } else if (isRegexVar(p)) {
                Pattern pattern = Pattern.compile(extractRegex(p));
                segments.add(Segment.regex(p, pattern));
            } else if (p.indexOf('?') >= 0 || p.indexOf('*') >= 0) {
                Pattern regex = compileQuestionMarkRegex(p);
                segments.add(Segment.regex(p, regex));
                continue;
            } else {
                segments.add(Segment.literal(p));
            }
        }
        return segments;
    }

    private static boolean isSimpleVar(String s) {
        return s.startsWith("{") && s.endsWith("}") && !s.contains(":");
    }

    private static boolean isRegexVar(String s) {
        return s.startsWith("{") && s.contains(":") && s.endsWith("}");
    }

    private static String extractRegex(String s) {
        int idx = s.indexOf(':');
        return s.substring(idx + 1, s.length() - 1);
    }

    private static Pattern compileQuestionMarkRegex(String segment) {
        StringBuilder regex = new StringBuilder("^");

        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '?') {
                regex.append('.');
            } else if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }

        regex.append('$');
        return Pattern.compile(regex.toString());
    }

    @Override
    public String toString() {
        return "Segment{" +
                "literal='" + literal + '\'' +
                '}';
    }
}
