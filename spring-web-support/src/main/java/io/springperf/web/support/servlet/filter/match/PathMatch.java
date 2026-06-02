package io.springperf.web.support.servlet.filter.match;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public interface PathMatch {

    boolean match(String path);

    static List<PathMatch> create(String[] supportedPathRules) {
        List<PathMatch> pathMatches = new ArrayList<>();
        if (supportedPathRules == null || supportedPathRules.length == 0) {
            return pathMatches;
        }
        // 精确匹配
        Set<String> exactMatches = new HashSet<>();
        // 前缀匹配
        List<String> prefixMatches = new ArrayList<>();
        // 后缀匹配
        List<String> suffixMatches = new ArrayList<>();
        for (String pattern : supportedPathRules) {
            if (pattern.contains("**") || pattern.contains("?")) {
                throw new IllegalArgumentException("pattern is no supported ：" + pattern);
            }
            if ("/".equals(pattern)) {
                //全匹配
                return pathMatches;
            } else if (pattern.endsWith("/*")) {
                // 前缀匹配，去掉末尾 /*
                prefixMatches.add(pattern.substring(0, pattern.length() - 2));
            } else if (pattern.startsWith("*.")) {
                // 后缀匹配
                suffixMatches.add(pattern.substring(1)); // 包括点，如 ".do"
            } else {
                // 精确匹配
                exactMatches.add(pattern);
            }
        }
        if (!exactMatches.isEmpty()) {
            pathMatches.add(new ExactMatch(exactMatches));
        }
        for (String prefixMatch : prefixMatches) {
            pathMatches.add(new PrefixMatch(prefixMatch));
        }
        for (String suffixMatch : suffixMatches) {
            pathMatches.add(new SuffixMatch(suffixMatch));
        }
        return pathMatches;
    }
}
