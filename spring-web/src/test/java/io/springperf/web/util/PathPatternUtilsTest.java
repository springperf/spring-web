package io.springperf.web.util;

import io.springperf.web.util.support.ContainmentResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class PathPatternUtilsTest {

    @Test
    void pathHaveWildcard_asterisk_returnsTrue() {
        assertTrue(PathPatternUtils.pathHaveWildcard("/api/**"));
    }

    @Test
    void pathHaveWildcard_questionMark_returnsTrue() {
        assertTrue(PathPatternUtils.pathHaveWildcard("/api/???"));
    }

    @Test
    void pathHaveWildcard_curlyBraces_returnsTrue() {
        assertTrue(PathPatternUtils.pathHaveWildcard("/api/{id}"));
    }

    @Test
    void pathHaveWildcard_literal_returnsFalse() {
        assertFalse(PathPatternUtils.pathHaveWildcard("/api/users"));
    }

    @Test
    void pathHaveWildcard_empty_returnsFalse() {
        assertFalse(PathPatternUtils.pathHaveWildcard(""));
    }

    @Test
    void patternContains_literalExactMatch_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                PathPatternUtils.patternContains("/api/users", "/api/users"));
    }

    @Test
    void patternContains_literalMismatch_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                PathPatternUtils.patternContains("/api/users", "/api/admin"));
    }

    @Test
    void patternContains_literalShorterContainer_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                PathPatternUtils.patternContains("/api", "/api/users"));
    }

    @Test
    void patternContains_singleWildcardMatches_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                PathPatternUtils.patternContains("/*/user", "/any/user"));
    }

    @Test
    void patternContains_singleWildcardMismatchLength_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                PathPatternUtils.patternContains("/*/user", "/a/b/user"));
    }

    @Test
    void patternContains_multiWildcardMatchesSuffix_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                PathPatternUtils.patternContains("/test/**", "/test/123/456"));
    }

    @Test
    void patternContains_multiWildcardMatchesEmpty_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                PathPatternUtils.patternContains("/test/**", "/test"));
    }

    @Test
    void patternContains_multiWildcardInMiddle_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                PathPatternUtils.patternContains("/a/**/b", "/a/x/y/b"));
    }

    @Test
    void patternContains_regexMatchesLiteral_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                PathPatternUtils.patternContains("/api/{id:\\d+}", "/api/123"));
    }

    @Test
    void patternContains_regexMismatch_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                PathPatternUtils.patternContains("/api/{id:\\d+}", "/api/abc"));
    }

    @Test
    void patternContains_regexContainerVsWildcard_returnsRuntime() {
        assertEquals(ContainmentResult.RUNTIME,
                PathPatternUtils.patternContains("/{id:\\d+}", "/*"));
    }

    @Test
    void patternListContains_oneAlways_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                PathPatternUtils.patternListContains(
                        Arrays.asList("/api/**", "/other"), "/api/users"));
    }

    @Test
    void patternListContains_allNever_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                PathPatternUtils.patternListContains(Arrays.asList("/a", "/b"), "/c"));
    }

    @Test
    void patternListContains_emptyList_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                PathPatternUtils.patternListContains(Collections.emptyList(), "/anything"));
    }

    @Test
    void patternContains_questionMarkMatches_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                PathPatternUtils.patternContains("/test/???", "/test/abc"));
    }

    @Test
    void patternContains_multiWildcardExtraContainerSegments_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                PathPatternUtils.patternContains("/**/extra", "/only"));
    }

    @Test
    void supportPatternParse_validPattern_returnsTrue() {
        assertTrue(PathPatternUtils.supportPatternParse("/api/{id}"));
    }

    @Test
    void supportPatternParse_simplePattern_returnsTrue() {
        assertTrue(PathPatternUtils.supportPatternParse("/api/users"));
    }

    @Test
    void getParser_returnsNonNull() {
        assertNotNull(PathPatternUtils.getParser());
    }

    @Test
    void getMatcher_returnsNonNull() {
        assertNotNull(PathPatternUtils.getMatcher());
    }
}
