package io.springperf.web.core.filter;

import io.springperf.web.util.support.ContainmentResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebFilterRegistrationTest {

    @Test
    void constructor_throwsOnNullFilter() {
        assertThrows(IllegalArgumentException.class, () -> new WebFilterRegistration(null));
    }

    @Test
    void constructor_wrapsFilter() {
        WebFilter filter = mock(WebFilter.class);
        WebFilterRegistration registration = new WebFilterRegistration(filter);
        assertSame(filter, registration.getFilter());
    }

    @Test
    void getComponentName_delegatesToWrappedFilter() {
        WebFilter filter = mock(WebFilter.class);
        when(filter.getComponentName()).thenReturn("myFilter");
        WebFilterRegistration registration = new WebFilterRegistration(filter);
        assertEquals("myFilter", registration.getComponentName());
    }

    @Test
    void order_returnsSetValue() {
        WebFilter filter = mock(WebFilter.class);
        WebFilterRegistration registration = new WebFilterRegistration(filter).order(42);
        assertEquals(42, registration.getOrder());
    }

    @Test
    void addPathPatterns_returnsSelf() {
        WebFilter filter = mock(WebFilter.class);
        WebFilterRegistration registration = new WebFilterRegistration(filter);
        assertSame(registration, registration.addPathPatterns("/api/*"));
    }

    @Test
    void excludePathPatterns_returnsSelf() {
        WebFilter filter = mock(WebFilter.class);
        WebFilterRegistration registration = new WebFilterRegistration(filter);
        assertSame(registration, registration.excludePathPatterns("/admin/*"));
    }

    // -------- matchPathRuleToCached 三段式推断（Servlet 语义） --------

    @Test
    void matchPathRuleToCached_emptyPatterns_returnsAlways() {
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        assertEquals(ContainmentResult.ALWAYS,
                registration.matchPathRuleToCached("/api/users"));
    }

    @Test
    void matchPathRuleToCached_includePrefixMatchesExact_returnsAlways() {
        // include=/api/* (servlet prefix) covers exact pathRule=/api/users → ALWAYS
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/*");
        assertEquals(ContainmentResult.ALWAYS,
                registration.matchPathRuleToCached("/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeNever_returnsNever() {
        // include=/api/* does NOT cover pathRule=/other → NEVER
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/*");
        assertEquals(ContainmentResult.NEVER,
                registration.matchPathRuleToCached("/other"));
    }

    @Test
    void matchPathRuleToCached_excludeAlways_returnsNever() {
        // include=/api/* covers /api/secret, exclude=/api/secret covers it too → NEVER
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/*");
        registration.excludePathPatterns("/api/secret");
        assertEquals(ContainmentResult.NEVER,
                registration.matchPathRuleToCached("/api/secret"));
    }

    @Test
    void matchPathRuleToCached_includeAlwaysExcludeDisjoint_returnsAlways() {
        // include=/api/* covers /api/users, exclude=/other disjoint → ALWAYS
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/*");
        registration.excludePathPatterns("/other");
        assertEquals(ContainmentResult.ALWAYS,
                registration.matchPathRuleToCached("/api/users"));
    }

    @Test
    void matchPathRuleToCached_includePrefixVsAntWildcard_returnsRuntime() {
        // include=/api/* (servlet prefix) vs pathRule=/{var} (Ant 变量，首段不固定) → RUNTIME
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/*");
        assertEquals(ContainmentResult.RUNTIME,
                registration.matchPathRuleToCached("/{var}"));
    }

    @Test
    void matchPathRuleToCached_includeAlwaysExcludeIntersect_returnsRuntime() {
        // include=/api/* covers /api/{id}, exclude=/api/secret 与 /api/{id} 有交集 → RUNTIME
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/*");
        registration.excludePathPatterns("/api/secret");
        assertEquals(ContainmentResult.RUNTIME,
                registration.matchPathRuleToCached("/api/{id}"));
    }

    @Test
    void matchPathRuleToCached_onlyExcludeNoInclude_returnsAlwaysWhenDisjoint() {
        // no includePatterns, exclude=/admin/*, pathRule=/api/users 前缀不相交 → ALWAYS
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.excludePathPatterns("/admin/*");
        assertEquals(ContainmentResult.ALWAYS,
                registration.matchPathRuleToCached("/api/users"));
    }

    // -------- matches 运行时路径匹配 --------

    @Test
    void matches_noPatterns_returnsTrue() {
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        assertTrue(registration.matches("/any/path"));
    }

    @Test
    void matches_includePrefixMatches_returnsTrue() {
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/*");
        assertTrue(registration.matches("/api/users"));
        assertTrue(registration.matches("/api"));
    }

    @Test
    void matches_includeNotMatched_returnsFalse() {
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/*");
        assertFalse(registration.matches("/other"));
    }

    @Test
    void matches_excludeTakesPrecedence_returnsFalse() {
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/*");
        registration.excludePathPatterns("/api/secret");
        assertFalse(registration.matches("/api/secret"), "exclude 命中应优先生效");
        assertTrue(registration.matches("/api/open"));
    }

    @Test
    void matches_excludeSufficientToDisable_returnsFalse() {
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.excludePathPatterns("/admin/*");
        assertFalse(registration.matches("/admin/panel"));
    }

    @Test
    void matches_suffixPattern() {
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("*.json");
        assertTrue(registration.matches("/api/data.json"));
        assertFalse(registration.matches("/api/data.xml"));
    }
}
