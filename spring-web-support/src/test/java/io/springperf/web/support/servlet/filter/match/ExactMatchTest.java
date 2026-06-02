package io.springperf.web.support.servlet.filter.match;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExactMatchTest {

    @Test
    void match_exactPath_returnsTrue() {
        Set<String> paths = new HashSet<>();
        paths.add("/api/health");
        ExactMatch match = new ExactMatch(paths);
        assertTrue(match.match("/api/health"));
    }

    @Test
    void match_differentPath_returnsFalse() {
        Set<String> paths = Collections.singleton("/api/health");
        ExactMatch match = new ExactMatch(paths);
        assertFalse(match.match("/api/info"));
    }

    @Test
    void match_subPath_returnsFalse() {
        Set<String> paths = Collections.singleton("/api");
        ExactMatch match = new ExactMatch(paths);
        assertFalse(match.match("/api/health"));
    }

    @Test
    void match_emptySet_returnsFalse() {
        ExactMatch match = new ExactMatch(Collections.emptySet());
        assertFalse(match.match("/any/path"));
    }

    @Test
    void match_multiplePaths_findsMatch() {
        Set<String> paths = new HashSet<>();
        paths.add("/api/health");
        paths.add("/api/info");
        paths.add("/api/metrics");
        ExactMatch match = new ExactMatch(paths);
        assertTrue(match.match("/api/metrics"));
    }

    @Test
    void implementsPathMatch() {
        ExactMatch match = new ExactMatch(Collections.singleton("/test"));
        assertInstanceOf(PathMatch.class, match);
    }
}