package io.springperf.web.support.servlet.filter.match;

public class SuffixMatch implements PathMatch {

    private final String suffix;

    public SuffixMatch(String suffix) {
        this.suffix = suffix;
    }

    @Override
    public boolean match(String path) {
        return path.endsWith(suffix);
    }
}
