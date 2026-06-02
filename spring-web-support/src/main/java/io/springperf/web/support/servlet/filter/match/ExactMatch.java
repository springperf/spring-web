package io.springperf.web.support.servlet.filter.match;

import java.util.Set;

public class ExactMatch implements PathMatch {

    private final Set<String> exactMatches;

    public ExactMatch(Set<String> exactMatches) {
        this.exactMatches = exactMatches;
    }

    @Override
    public boolean match(String path) {
        return exactMatches.contains(path);
    }
}
