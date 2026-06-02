package io.springperf.web.support.servlet.filter.match;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathMatchTest {

    // ---- ExactMatch ----

    @Test
    void exactMatch_matchesConfiguredPaths() {
        ExactMatch match = new ExactMatch(new HashSet<>(java.util.Arrays.asList("/login", "/logout")));

        assertTrue(match.match("/login"));
        assertTrue(match.match("/logout"));
        assertFalse(match.match("/login/extra"));
        assertFalse(match.match("/logn"));
        assertFalse(match.match(""));
    }

    @Test
    void exactMatch_emptySet_matchesNothing() {
        ExactMatch match = new ExactMatch(new HashSet<>());

        assertFalse(match.match("/"));
        assertFalse(match.match("/login"));
        assertFalse(match.match(""));
    }

    @Test
    void exactMatch_nullInput_returnsFalse() {
        ExactMatch match = new ExactMatch(new HashSet<>(java.util.Arrays.asList("/foo")));

        assertFalse(match.match(null));
    }

    // ---- PrefixMatch ----

    @Test
    void prefixMatch_matchesExactPrefix() {
        PrefixMatch match = new PrefixMatch("/api");

        assertTrue(match.match("/api"));
    }

    @Test
    void prefixMatch_matchesSubPath() {
        PrefixMatch match = new PrefixMatch("/api");

        assertTrue(match.match("/api/users"));
        assertTrue(match.match("/api/v1/orders"));
    }

    @Test
    void prefixMatch_doesNotMatchSimilarPrefixWithoutSlash() {
        PrefixMatch match = new PrefixMatch("/api");

        assertFalse(match.match("/apiusers"));
    }

    @Test
    void prefixMatch_doesNotMatchUnrelatedPath() {
        PrefixMatch match = new PrefixMatch("/api");

        assertFalse(match.match("/other"));
        assertFalse(match.match("/"));
    }

    @Test
    void prefixMatch_rootPrefix_matchesAll() {
        PrefixMatch match = new PrefixMatch("");

        assertTrue(match.match(""));
        assertTrue(match.match("/foo"));
    }

    // ---- SuffixMatch ----

    @Test
    void suffixMatch_matchesEndingWithSuffix() {
        SuffixMatch match = new SuffixMatch(".do");

        assertTrue(match.match("/test.do"));
        assertTrue(match.match("/foo/bar.do"));
        assertTrue(match.match(".do"));
    }

    @Test
    void suffixMatch_doesNotMatchWithoutSuffix() {
        SuffixMatch match = new SuffixMatch(".do");

        assertFalse(match.match("/test"));
        assertFalse(match.match("/test.txt"));
        assertFalse(match.match(""));
    }

    @Test
    void suffixMatch_matchesDotHtmlSuffix() {
        SuffixMatch match = new SuffixMatch(".html");

        assertTrue(match.match("/page.html"));
        assertFalse(match.match("/page.htm"));
    }

    @Test
    void suffixMatch_nullInput_throwsNullPointerException() {
        SuffixMatch match = new SuffixMatch(".do");

        assertThrows(NullPointerException.class, () -> match.match(null));
    }

    // ---- PathMatch.create ----

    @Test
    void create_withNull_returnsEmptyList() {
        List<PathMatch> matches = PathMatch.create(null);

        assertTrue(matches.isEmpty());
    }

    @Test
    void create_withEmptyArray_returnsEmptyList() {
        List<PathMatch> matches = PathMatch.create(new String[0]);

        assertTrue(matches.isEmpty());
    }

    @Test
    void create_withRootPattern_returnsEmptyList() {
        List<PathMatch> matches = PathMatch.create(new String[]{"/"});

        assertTrue(matches.isEmpty());
    }

    @Test
    void create_withRootPattern_first_resetsToEmpty() {
        List<PathMatch> matches = PathMatch.create(new String[]{"/login", "/"});

        assertTrue(matches.isEmpty());
    }

    @Test
    void create_withExactPatterns() {
        List<PathMatch> matches = PathMatch.create(new String[]{"/login", "/logout"});

        assertEquals(1, matches.size());
        assertInstanceOf(ExactMatch.class, matches.get(0));
        assertTrue(matches.get(0).match("/login"));
        assertTrue(matches.get(0).match("/logout"));
        assertFalse(matches.get(0).match("/other"));
    }

    @Test
    void create_withPrefixPattern() {
        List<PathMatch> matches = PathMatch.create(new String[]{"/api/*"});

        assertEquals(1, matches.size());
        assertInstanceOf(PrefixMatch.class, matches.get(0));
        assertTrue(matches.get(0).match("/api"));
        assertTrue(matches.get(0).match("/api/users"));
        assertFalse(matches.get(0).match("/apiusers"));
    }

    @Test
    void create_withSuffixPattern() {
        List<PathMatch> matches = PathMatch.create(new String[]{"*.do"});

        assertEquals(1, matches.size());
        assertInstanceOf(SuffixMatch.class, matches.get(0));
        assertTrue(matches.get(0).match("/test.do"));
        assertFalse(matches.get(0).match("/test.txt"));
    }

    @Test
    void create_withAllPatternTypes() {
        List<PathMatch> matches = PathMatch.create(new String[]{"/login", "/api/*", "*.do"});

        assertEquals(3, matches.size());
        assertInstanceOf(ExactMatch.class, matches.get(0));
        assertInstanceOf(PrefixMatch.class, matches.get(1));
        assertInstanceOf(SuffixMatch.class, matches.get(2));
    }

    @Test
    void create_withMultiplePrefixPatterns() {
        List<PathMatch> matches = PathMatch.create(new String[]{"/api/*", "/admin/*"});

        assertEquals(2, matches.size());
        assertInstanceOf(PrefixMatch.class, matches.get(0));
        assertInstanceOf(PrefixMatch.class, matches.get(1));
    }

    @Test
    void create_withDoubleStarPattern_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> PathMatch.create(new String[]{"/foo/**"}));
    }

    @Test
    void create_withQuestionMarkPattern_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> PathMatch.create(new String[]{"/foo/?"}));
    }

    @Test
    void create_withExactDuplicatePatterns_deduplicates() {
        List<PathMatch> matches = PathMatch.create(new String[]{"/foo", "/foo", "/bar"});

        assertEquals(1, matches.size());
        assertInstanceOf(ExactMatch.class, matches.get(0));
    }

    @Test
    void create_withDotPrefixPattern() {
        List<PathMatch> matches = PathMatch.create(new String[]{"/.well-known/*"});

        assertEquals(1, matches.size());
        assertInstanceOf(PrefixMatch.class, matches.get(0));
        assertTrue(matches.get(0).match("/.well-known"));
        assertTrue(matches.get(0).match("/.well-known/security"));
    }

    @Test
    void create_withMultipleSuffixPatterns() {
        List<PathMatch> matches = PathMatch.create(new String[]{"*.json", "*.xml"});

        assertEquals(2, matches.size());
        assertInstanceOf(SuffixMatch.class, matches.get(0));
        assertInstanceOf(SuffixMatch.class, matches.get(1));
    }
}
