package io.springperf.web.support.servlet.filter.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SuffixMatchTest {

    @Test
    void match_endsWithSuffix_returnsTrue() {
        SuffixMatch match = new SuffixMatch(".html");
        assertTrue(match.match("/page.html"));
    }

    @Test
    void match_doesNotEndWithSuffix_returnsFalse() {
        SuffixMatch match = new SuffixMatch(".html");
        assertFalse(match.match("/page.htm"));
    }

    @Test
    void match_exactSuffixOnly_returnsTrue() {
        SuffixMatch match = new SuffixMatch(".json");
        assertTrue(match.match(".json"));
    }

    @Test
    void match_emptySuffix_returnsTrueForAll() {
        SuffixMatch match = new SuffixMatch("");
        assertTrue(match.match(""));
        assertTrue(match.match("/any/path"));
    }

    @Test
    void match_emptyString_returnsFalse() {
        SuffixMatch match = new SuffixMatch(".html");
        assertFalse(match.match(""));
    }

    @Test
    void implementsPathMatch() {
        SuffixMatch match = new SuffixMatch(".txt");
        assertInstanceOf(PathMatch.class, match);
    }
}