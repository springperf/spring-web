package io.springperf.web.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.springperf.web.util.support.ContainmentResult;

/** Servlet 模式校验与三段式编译期推断的组合边界。 */
class ServletFilterPatternUtilsEdgeTest {

    @Test
    void validateServletPattern_coversEveryDecisionBranch() {
        assertNotNull(ServletFilterPatternUtils.validateServletPattern(null));
        assertNotNull(ServletFilterPatternUtils.validateServletPattern(""));

        assertNull(ServletFilterPatternUtils.validateServletPattern("/*"));
        assertNull(ServletFilterPatternUtils.validateServletPattern("/"));
        assertNull(ServletFilterPatternUtils.validateServletPattern("/api/*"));
        assertNull(ServletFilterPatternUtils.validateServletPattern("*.json"));
        assertNull(ServletFilterPatternUtils.validateServletPattern("/exact/path"));

        assertTrue(ServletFilterPatternUtils.validateServletPattern("api/test/*").contains("不符合 Servlet URL 模式规范"));
        assertTrue(ServletFilterPatternUtils.validateServletPattern("/api/{id}").contains("不符合 Servlet URL 模式规范"));
        assertTrue(ServletFilterPatternUtils.validateServletPattern("api/test").contains("缺少前导 /"));
    }

    @Test
    void matchPathRuleToCached_combinesIncludeAndExcludeResults() {
        assertEquals(ContainmentResult.ALWAYS, ServletFilterPatternUtils.matchPathRuleToCached(Collections.emptyList(),
                Collections.emptyList(), "/api"));

        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.matchPathRuleToCached(List.of("/*"), Collections.emptyList(), "/api"));
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.matchPathRuleToCached(List.of("/other/*"), Collections.emptyList(), "/api"));
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.matchPathRuleToCached(Collections.emptyList(), List.of("/other/*"), "/api"));
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.matchPathRuleToCached(Collections.emptyList(), List.of("/api/*"), "/api"),
                "排除集完全包含 pathRule 时应判定为永不适用");
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.matchPathRuleToCached(List.of("/*"), List.of("/*"), "/api"));
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.matchPathRuleToCached(List.of("/api/*"), List.of("/other/*"), "*.json"),
                "prefix 与 suffix 的包含关系只能在运行期判定");
    }

    @Test
    void patternListContains_tracksRuntimeAndNever() {
        assertEquals(ContainmentResult.ALWAYS,
                ServletFilterPatternUtils.patternListContains(List.of("/other/*", "/*"), "/api"));
        assertEquals(ContainmentResult.RUNTIME,
                ServletFilterPatternUtils.patternListContains(List.of("/other/*", "/api/*"), "*.json"));
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.patternListContains(List.of("/other/*", "/else/*"), "/api"));
        assertEquals(ContainmentResult.NEVER,
                ServletFilterPatternUtils.patternListContains(Collections.emptyList(), "/api"));
    }

    @Test
    void patternsDisjoint_handlesMixedServletAndAntPatterns() {
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/*", "/api"));
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/api", "/*"));
        assertTrue(ServletFilterPatternUtils.patternsDisjoint("/api/*", "/other/*"));
        assertTrue(ServletFilterPatternUtils.patternsDisjoint("*.json", "*.xml"));
        assertTrue(ServletFilterPatternUtils.patternsDisjoint("/exact", "/other"));
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/api/*", "/api/x"));
        assertFalse(ServletFilterPatternUtils.patternsDisjoint("/api/**", "/api/x"));
    }

    @Test
    void matches_coversExactPrefixSuffixAndAll() {
        assertTrue(ServletFilterPatternUtils.matches("/*", "/anything"));
        assertTrue(ServletFilterPatternUtils.matches("/", "/anything"));
        assertTrue(ServletFilterPatternUtils.matches("/api/*", "/api"));
        assertTrue(ServletFilterPatternUtils.matches("/api/*", "/api/users"));
        assertFalse(ServletFilterPatternUtils.matches("/api/*", "/apiusers"));
        assertTrue(ServletFilterPatternUtils.matches("*.json", "/api/data.json"));
        assertFalse(ServletFilterPatternUtils.matches("*.json", "/api/data.xml"));
        assertTrue(ServletFilterPatternUtils.matches("/exact", "/exact"));
        assertFalse(ServletFilterPatternUtils.matches("/exact", "/exact/child"));
    }

}
