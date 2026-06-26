package io.springperf.web.util;

import io.springperf.web.util.support.ContainmentResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ServletFilterPatternUtils} 的全面测试。
 * <p>覆盖点：
 * <ul>
 *   <li>pattern 类型检测（ALL / PREFIX / SUFFIX）</li>
 *   <li>运行时匹配（{@link ServletFilterPatternUtils#matches}）</li>
 *   <li>三段式编译期包含关系推断（{@link ServletFilterPatternUtils#patternContains}）</li>
 *   <li>三段式缓存推断顶层入口（{@link ServletFilterPatternUtils#matchPathRuleToCached}）</li>
 *   <li>不相交判断（{@link ServletFilterPatternUtils#patternsDisjoint}）</li>
 * </ul>
 */
class ServletFilterPatternUtilsTest {

    // ========== Pattern type detection ==========

    @Test
    void isAllPattern_returnsTrueForSlashStar() {
        assertTrue(ServletFilterPatternUtils.isAllPattern("/*"));
    }

    @Test
    void isAllPattern_returnsTrueForSlash() {
        assertTrue(ServletFilterPatternUtils.isAllPattern("/"));
    }

    @Test
    void isAllPattern_returnsFalseForPrefixPattern() {
        assertFalse(ServletFilterPatternUtils.isAllPattern("/api/*"));
    }

    @Test
    void isPrefixPattern_returnsTrueForApiPrefix() {
        assertTrue(ServletFilterPatternUtils.isPrefixPattern("/api/*"));
    }

    @Test
    void isPrefixPattern_returnsTrueForNestedPrefix() {
        assertTrue(ServletFilterPatternUtils.isPrefixPattern("/api/users/*"));
    }

    @Test
    void isPrefixPattern_returnsFalseForAll() {
        assertFalse(ServletFilterPatternUtils.isPrefixPattern("/*"));
    }

    @Test
    void isPrefixPattern_returnsFalseForExact() {
        assertFalse(ServletFilterPatternUtils.isPrefixPattern("/api/health"));
    }

    @Test
    void isSuffixPattern_returnsTrue() {
        assertTrue(ServletFilterPatternUtils.isSuffixPattern("*.json"));
    }

    @Test
    void isSuffixPattern_returnsFalseForPrefix() {
        assertFalse(ServletFilterPatternUtils.isSuffixPattern("/api/*"));
    }

    // ========== Extract prefix/suffix ==========

    @Test
    void extractPrefix_returnsCorrectPrefix() {
        assertEquals("/api", ServletFilterPatternUtils.extractPrefix("/api/*"));
        assertEquals("/api/users", ServletFilterPatternUtils.extractPrefix("/api/users/*"));
    }

    @Test
    void extractSuffix_returnsCorrectSuffix() {
        assertEquals(".json", ServletFilterPatternUtils.extractSuffix("*.json"));
        assertEquals(".do", ServletFilterPatternUtils.extractSuffix("*.do"));
    }

    // ========== Runtime matching ==========

    @ParameterizedTest
    @CsvSource({
            "/*, /any/path, true",
            "/*, /, true",
            "/, /any/path, true",
            "/api/*, /api/users, true",
            "/api/*, /api/users/details, true",
            "/api/*, /api, true",
            "/api/*, /other, false",
            "/api/*, /apis, false",
            "*.json, /api/data.json, true",
            "*.json, /api/data.xml, false",
            "*.json, /api/data.JSON, false",
            "/api/health, /api/health, true",
            "/api/health, /api/health/, false",
            "/api/health, /api/health/detail, false",
    })
    void matches(String pattern, String lookupPath, boolean expected) {
        assertEquals(expected, ServletFilterPatternUtils.matches(pattern, lookupPath));
    }

    // ========== patternContains ==========

    // --- ALL container ---

    @Test
    void patternContains_all_vsAnything_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternContains("/*", "/api/users"));
    }

    @Test
    void patternContains_all_vsWildcard_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternContains("/*", "/api/{id}"));
    }

    @Test
    void patternContains_all_vsRecursive_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternContains("/*", "/api/**"));
    }

    // --- PREFIX container ---

    @Test
    void patternContains_prefix_vsExactUnderPrefix_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternContains("/api/*", "/api/users"));
    }

    @Test
    void patternContains_prefix_vsExactSamePrefix_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternContains("/api/*", "/api"));
    }

    @Test
    void patternContains_prefix_vsExactDifferent_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.patternContains("/api/*", "/other"));
    }

    @Test
    void patternContains_prefix_vsSubPrefix_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternContains("/api/*", "/api/users/*"));
    }

    @Test
    void patternContains_prefix_vsSiblingPrefix_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.patternContains("/api/*", "/other/*"));
    }

    @Test
    void patternContains_prefix_vsSuffix_returnsRuntime() {
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.patternContains("/api/*", "*.json"));
    }

    @Test
    void patternContains_prefix_vsAll_returnsRuntime() {
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.patternContains("/api/*", "/*"));
    }

    @Test
    void patternContains_prefix_vsAntPathVarUnderPrefix_returnsAlways() {
        // /api/* (servlet prefix) definitely contains all paths matching /api/{id}
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternContains("/api/*", "/api/{id}"));
    }

    @Test
    void patternContains_prefix_vsAntRecursiveUnderPrefix_returnsAlways() {
        // /api/* (servlet prefix) definitely contains all paths matching /api/**
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternContains("/api/*", "/api/**"));
    }

    @Test
    void patternContains_prefix_vsAntWildcardDifferentPrefix_returnsNever() {
        // /api/* does NOT contain /other/** — completely different prefix
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.patternContains("/api/*", "/other/**"));
    }

    @Test
    void patternContains_prefix_vsAntVariableFirstSegment_returnsRuntime() {
        // /api/* vs /{var} — first segment is variable, can't prove containment
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.patternContains("/api/*", "/{var}"));
    }

    @Test
    void patternContains_prefix_vsAntWildcardRoot_returnsRuntime() {
        // /api/* vs /* — all root wildcard, could match any single segment
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.patternContains("/api/*", "/*"));
    }

    @Test
    void patternContains_prefix_vsExactSamePrefixChars_returnsNever() {
        // /api/* does NOT contain /api-extra — not under /api/ prefix
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.patternContains("/api/*", "/api-extra"));
    }

    // --- SUFFIX container ---

    @Test
    void patternContains_suffix_vsExactMatching_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternContains("*.json", "/api/data.json"));
    }

    @Test
    void patternContains_suffix_vsExactNotMatching_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.patternContains("*.json", "/api/data.xml"));
    }

    @Test
    void patternContains_suffix_vsSuffixSubset_returnsAlways() {
        // *.json contains *.data.json (every path ending with .data.json also ends with .json)
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternContains("*.json", "*.data.json"));
    }

    @Test
    void patternContains_suffix_vsSuffixDifferent_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.patternContains("*.json", "*.xml"));
    }

    @Test
    void patternContains_suffix_vsPrefix_returnsRuntime() {
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.patternContains("*.json", "/api/*"));
    }

    @Test
    void patternContains_suffix_vsAll_returnsRuntime() {
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.patternContains("*.json", "/*"));
    }

    @Test
    void patternContains_suffix_vsAntWildcard_returnsRuntime() {
        // Can't prove at compile time: /api/{id} might or might not end with .json
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.patternContains("*.json", "/api/{id}"));
    }

    @Test
    void patternContains_suffix_vsAntRecursive_returnsRuntime() {
        // *.json vs /api/** — /api/** could match /api/data.json
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.patternContains("*.json", "/api/**"));
    }

    // --- EXACT container ---

    @Test
    void patternContains_exact_vsSame_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternContains("/api/health", "/api/health"));
    }

    @Test
    void patternContains_exact_vsDifferent_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.patternContains("/api/health", "/api/users"));
    }

    @Test
    void patternContains_exact_vsAntWildcard_returnsRuntime() {
        // exact path can't contain Ant variable patterns at compile time
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.patternContains("/api/health", "/api/{id}"));
    }

    // ========== patternsDisjoint ==========

    @Test
    void patternsDisjoint_all_notDisjointWithAnything() {
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/*", "/api/*"));
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/api/*", "/*"));
    }

    @Test
    void patternsDisjoint_prefixVsPrefixDisjoint() {
        assertTrue(ServletFilterPatternUtils.patternsDisjoint("/api/*", "/other/*"));
    }

    @Test
    void patternsDisjoint_prefixVsPrefixNotDisjoint() {
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/api/*", "/api/users/*"));
    }

    @Test
    void patternsDisjoint_prefixVsExactDisjoint() {
        assertTrue(ServletFilterPatternUtils.patternsDisjoint("/api/*", "/other"));
    }

    @Test
    void patternsDisjoint_prefixVsExactNotDisjoint() {
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/api/*", "/api/users"));
    }

    @Test
    void patternsDisjoint_prefixVsSuffix_notDisjoint() {
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/api/*", "*.json"));
    }

    @Test
    void patternsDisjoint_suffixVsSuffixDisjoint() {
        assertTrue(ServletFilterPatternUtils.patternsDisjoint("*.json", "*.xml"));
    }

    @Test
    void patternsDisjoint_suffixVsSuffixNotDisjoint() {
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("*.json", "*.data.json"));
    }

    @Test
    void patternsDisjoint_suffixVsExactDisjoint() {
        assertTrue(ServletFilterPatternUtils.patternsDisjoint("*.json", "/api/data.xml"));
    }

    @Test
    void patternsDisjoint_suffixVsExactNotDisjoint() {
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("*.json", "/api/data.json"));
    }

    @Test
    void patternsDisjoint_exactVsExactDisjoint() {
        assertTrue(ServletFilterPatternUtils.patternsDisjoint("/a", "/b"));
    }

    @Test
    void patternsDisjoint_exactVsExactNotDisjoint() {
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/a", "/a"));
    }

    @Test
    void patternsDisjoint_exactWithAntWildcard_notDisjoint() {
        // Can't prove disjoint when one side has Ant wildcards
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/api/secret", "/api/{id}"));
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/api/{id}", "/api/secret"));
    }

    @Test
    void patternsDisjoint_suffixVsExactWithAntWildcard_notDisjoint() {
        // *.json vs /api/{id} — /api/{id} could match /api/data.json
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("*.json", "/api/{id}"));
    }

    @Test
    void patternsDisjoint_prefixVsExactWithAntWildcard_notDisjoint() {
        // /api/* vs /api/{id} — /api/{id} matches requests under /api/
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/api/*", "/api/{id}"));
    }

    @Test
    void patternsDisjoint_prefixVsExactWithAntWildcardDifferentPrefix_disjoint() {
        // /api/* vs /other/{id} — completely different prefix
        assertTrue(ServletFilterPatternUtils.patternsDisjoint("/api/*", "/other/{id}"));
    }

    // ========== patternListContains ==========

    @Test
    void patternListContains_matchingExact_returnsAlways() {
        List<String> patterns = Arrays.asList("/api/*", "/other");
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternListContains(patterns, "/api/users"));
    }

    @Test
    void patternListContains_noneMatch_returnsNever() {
        List<String> patterns = Arrays.asList("/admin/*", "/other");
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.patternListContains(patterns, "/api/users"));
    }

    @Test
    void patternListContains_someRuntime_returnsRuntime() {
        List<String> patterns = Arrays.asList("*.json", "/api/*");
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.patternListContains(patterns, "/other/{id}"));
    }

    // ========== matchPathRuleToCached ==========

    @Test
    void matchPathRuleToCached_emptyPatterns_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.matchPathRuleToCached(
                        Collections.emptyList(), Collections.emptyList(), "/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeMatches_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.matchPathRuleToCached(
                        Arrays.asList("/api/*"), Collections.emptyList(), "/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeNever_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.matchPathRuleToCached(
                        Arrays.asList("/api/*"), Collections.emptyList(), "/other"));
    }

    @Test
    void matchPathRuleToCached_excludeAlways_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.matchPathRuleToCached(
                        Arrays.asList("/api/*"), Arrays.asList("/api/secret"), "/api/secret"));
    }

    @Test
    void matchPathRuleToCached_includeAlwaysExcludeDisjoint_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.matchPathRuleToCached(
                        Arrays.asList("/api/*"), Arrays.asList("/other"), "/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeAlwaysExcludeIntersect_returnsRuntime() {
        // exclude=/api/secret and pathRule=/api/{id} intersect → RUNTIME
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.matchPathRuleToCached(
                        Arrays.asList("/api/*"), Arrays.asList("/api/secret"), "/api/{id}"));
    }

    @Test
    void matchPathRuleToCached_includeRuntime_returnsRuntime() {
        // include=*.json vs pathRule=/api/{id} → RUNTIME (suffix can't prove containment of Ant variable)
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.matchPathRuleToCached(
                        Arrays.asList("*.json"), Collections.emptyList(), "/api/{id}"));
    }

    @Test
    void matchPathRuleToCached_onlyExcludeDisjoint_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.matchPathRuleToCached(
                        Collections.emptyList(), Arrays.asList("/admin/*"), "/api/users"));
    }

    @Test
    void matchPathRuleToCached_onlyExcludeNever_returnsAlways() {
        // exclude=/other/* vs pathRule=/api/users → exclude NEVER → overall ALWAYS
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.matchPathRuleToCached(
                        Collections.emptyList(), Arrays.asList("/other/*"), "/api/users"));
    }

    @Test
    void matchPathRuleToCached_onlyExcludeRuntime_returnsRuntime() {
        // exclude=*.json vs Ant pathRule=/api/{id} → exclude RUNTIME → overall RUNTIME
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.matchPathRuleToCached(
                        Collections.emptyList(), Arrays.asList("*.json"), "/api/{id}"));
    }

    @Test
    void matchPathRuleToCached_bothRuntime_returnsRuntime() {
        // include=*.json (RUNTIME), exclude=jsp (RUNTIME), pathRule=/api/{id}
        // includeResult=RUNTIME, doesn't hit ALWAYS+NEVER branch → final return RUNTIME
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.matchPathRuleToCached(
                        Arrays.asList("*.json"), Arrays.asList("*.jsp"), "/api/{id}"));
    }

    @Test
    void matchPathRuleToCached_includeNeverExcludeAlways_returnsNever() {
        // include=/other/* vs pathRule=/api/users → NEVER, short-circuit returns NEVER
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.matchPathRuleToCached(
                        Arrays.asList("/other/*"), Arrays.asList("*.json"), "/api/users"));
    }
}
