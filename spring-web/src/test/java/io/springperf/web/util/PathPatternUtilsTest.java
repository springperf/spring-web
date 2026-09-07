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

    // ---- matchPathRuleToCached ----

    @Test
    void matchPathRuleToCached_emptyPatterns_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                PathPatternUtils.matchPathRuleToCached(Collections.emptyList(), Collections.emptyList(), "/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeMatches_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                PathPatternUtils.matchPathRuleToCached(Arrays.asList("/api/**"), Collections.emptyList(), "/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeNever_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                PathPatternUtils.matchPathRuleToCached(Arrays.asList("/api/**"), Collections.emptyList(), "/other"));
    }

    @Test
    void matchPathRuleToCached_excludeAlways_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                PathPatternUtils.matchPathRuleToCached(
                        Arrays.asList("/**"), Arrays.asList("/api/**"), "/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeAlwaysExcludeDisjoint_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                PathPatternUtils.matchPathRuleToCached(
                        Arrays.asList("/api/**"), Arrays.asList("/admin/**"), "/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeAlwaysExcludeIntersect_returnsRuntime() {
        assertEquals(ContainmentResult.RUNTIME,
                PathPatternUtils.matchPathRuleToCached(
                        Arrays.asList("/api/**"), Arrays.asList("/api/secret"), "/api/{id}"));
    }

    @Test
    void matchPathRuleToCached_onlyExcludeDisjoint_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                PathPatternUtils.matchPathRuleToCached(
                        Collections.emptyList(), Arrays.asList("/admin/**"), "/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeRuntime_returnsRuntime() {
        assertEquals(ContainmentResult.RUNTIME,
                PathPatternUtils.matchPathRuleToCached(
                        Arrays.asList("/api/*"), Collections.emptyList(), "/api/**"));
    }

    @Test
    void matchPathRuleToCached_onlyExcludeIntersects_returnsRuntime() {
        assertEquals(ContainmentResult.RUNTIME,
                PathPatternUtils.matchPathRuleToCached(
                        Collections.emptyList(), Arrays.asList("/api/secret"), "/api/{id}"));
    }

    // ---- patternsDisjoint ----

    @Test
    void patternsDisjoint_literalMismatch_returnsTrue() {
        assertTrue(PathPatternUtils.patternsDisjoint("/api/users", "/admin/users"));
    }

    @Test
    void patternsDisjoint_literalWithWildcard_returnsFalse() {
        assertFalse(PathPatternUtils.patternsDisjoint("/api/users", "/api/{id}"));
    }

    @Test
    void patternsDisjoint_literalVsRegexMismatch_returnsTrue() {
        assertTrue(PathPatternUtils.patternsDisjoint("/api/abc", "/api/{id:\\d+}"));
    }

    @Test
    void patternsDisjoint_literalVsRegexMatch_returnsFalse() {
        assertFalse(PathPatternUtils.patternsDisjoint("/api/123", "/api/{id:\\d+}"));
    }

    @Test
    void patternsDisjoint_differentDepthAllLiterals_returnsTrue() {
        // /a/b/c 与 /a/b 深度不同且剩余段均为字面量 → 确定不相交
        assertTrue(PathPatternUtils.patternsDisjoint("/a/b/c", "/a/b"));
    }

    @Test
    void patternsDisjoint_multiWildcard_notDisjoint() {
        // ** 可匹配任意段 → 无法证明不相交
        assertFalse(PathPatternUtils.patternsDisjoint("/a/**", "/a/b"));
        assertFalse(PathPatternUtils.patternsDisjoint("/a/b", "/a/**"));
    }

    @Test
    void patternsDisjoint_trailingWildcard_notDisjoint() {
        // /a/* 也匹配 /a → 剩余的 * 非字面量 → 不相交为 false
        assertFalse(PathPatternUtils.patternsDisjoint("/a/*", "/a"));
    }

    @Test
    void patternsDisjoint_wildcardVsVar_notDisjoint() {
        // 两个非字面量/非正则段组合 → 可能相交
        assertFalse(PathPatternUtils.patternsDisjoint("/a/*", "/a/{id}"));
    }

    @Test
    void comparePathRuleSpecificity_variableBeatsWildcard() {
        assertTrue(PathPatternUtils.comparePathRuleSpecificity("/user/{id}", "/user/*") < 0,
                "路径变量应比单通配符更精确（排前面）");
    }

    @Test
    void comparePathRuleSpecificity_literalBeatsVariable() {
        assertTrue(PathPatternUtils.comparePathRuleSpecificity("/user/me", "/user/{id}") < 0,
                "字面量段应比路径变量更精确");
    }

    @Test
    void comparePathRuleSpecificity_exactBeatsCatchAll() {
        assertTrue(PathPatternUtils.comparePathRuleSpecificity("/user", "/user/**") < 0,
                "精确路径应比 catch-all 更精确");
    }

    @Test
    void comparePathRuleSpecificity_shorterExactPrefixWins() {
        // 公共前缀相同：段数更少（精确）优先
        assertTrue(PathPatternUtils.comparePathRuleSpecificity("/a/b", "/a/b/**") < 0);
    }

    @Test
    void comparePathRuleSpecificity_equalPatterns_returnsZero() {
        assertEquals(0, PathPatternUtils.comparePathRuleSpecificity("/user/{id}", "/user/{id}"));
    }

    @Test
    void comparePathRuleSpecificity_sameSpecificity_keepsOrder() {
        // 同特异性（同段位同为变量/通配符）→ 0，由稳定排序保持注册顺序
        assertEquals(0, PathPatternUtils.comparePathRuleSpecificity("/user/{a}", "/user/{b}"));
    }
}
