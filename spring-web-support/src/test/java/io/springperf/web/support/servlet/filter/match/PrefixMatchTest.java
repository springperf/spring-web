package io.springperf.web.support.servlet.filter.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrefixMatchTest {

    @Test
    void match_exactPrefixMatch_returnsTrue() {
        PrefixMatch match = new PrefixMatch("/api");
        assertTrue(match.match("/api"));
    }

    @Test
    void match_pathStartsWithPrefix_returnsTrue() {
        PrefixMatch match = new PrefixMatch("/api");
        assertTrue(match.match("/api/health"));
    }

    @Test
    void match_pathDoesNotStartWithPrefix_returnsFalse() {
        PrefixMatch match = new PrefixMatch("/api");
        assertFalse(match.match("/other/health"));
    }

    @Test
    void match_similarButNotMatching_returnsFalse() {
        PrefixMatch match = new PrefixMatch("/api");
        assertFalse(match.match("/api2/health"));
    }

    @Test
    void match_emptyPrefix_returnsTrueForRoot() {
        PrefixMatch match = new PrefixMatch("");
        assertTrue(match.match(""));
    }

    @Test
    void match_singleSlashPrefix() {
        PrefixMatch match = new PrefixMatch("/");
        assertTrue(match.match("/"));
        // "/anything" would need "//anything" match which is correct behavior
    }

    @Test
    void implementsPathMatch() {
        PrefixMatch match = new PrefixMatch("/test");
        assertInstanceOf(PathMatch.class, match);
    }
}