package io.springperf.web.support.servlet.filter.match;

public class PrefixMatch implements PathMatch {

    private String prefix;

    public PrefixMatch(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public boolean match(String path) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
