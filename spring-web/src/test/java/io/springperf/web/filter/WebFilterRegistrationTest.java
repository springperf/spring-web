package io.springperf.web.filter;

import io.springperf.web.util.support.ContainmentResult;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;

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
        assertSame(registration, registration.addPathPatterns("/api/**"));
    }

    @Test
    void excludePathPatterns_returnsSelf() {
        WebFilter filter = mock(WebFilter.class);
        WebFilterRegistration registration = new WebFilterRegistration(filter);
        assertSame(registration, registration.excludePathPatterns("/admin/**"));
    }

    // -------- matchPathRuleToCached 三段式推断 --------

    @Test
    void matchPathRuleToCached_emptyPatterns_returnsAlways() {
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        assertEquals(ContainmentResult.ALWAYS,
                registration.matchPathRuleToCached("/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeMatches_returnsAlways() {
        // include=/api/** covers pathRule=/api/users → ALWAYS
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/**");
        assertEquals(ContainmentResult.ALWAYS,
                registration.matchPathRuleToCached("/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeNever_returnsNever() {
        // include=/api/** does NOT cover pathRule=/other → NEVER
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/**");
        assertEquals(ContainmentResult.NEVER,
                registration.matchPathRuleToCached("/other"));
    }

    @Test
    void matchPathRuleToCached_excludeAlways_returnsNever() {
        // include=/api/** covers pathRule=/api/secret, exclude=/api/secret covers it too → NEVER
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/**");
        registration.excludePathPatterns("/api/secret");
        assertEquals(ContainmentResult.NEVER,
                registration.matchPathRuleToCached("/api/secret"));
    }

    @Test
    void matchPathRuleToCached_includeAlwaysExcludeDisjoint_returnsAlways() {
        // include=/api/** covers pathRule=/api/users, exclude=/other 无交集 → ALWAYS
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/**");
        registration.excludePathPatterns("/other");
        assertEquals(ContainmentResult.ALWAYS,
                registration.matchPathRuleToCached("/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeRuntime_returnsRuntime() {
        // include=/api/* vs pathRule=/api/** → RUNTIME
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/*");
        assertEquals(ContainmentResult.RUNTIME,
                registration.matchPathRuleToCached("/api/**"));
    }

    @Test
    void matchPathRuleToCached_includeAlwaysExcludeRuntime_returnsRuntime() {
        // include=/api/** covers pathRule=/api/{id}, exclude=/api/secret doesn't always → RUNTIME
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.addPathPatterns("/api/**");
        registration.excludePathPatterns("/api/secret");
        assertEquals(ContainmentResult.RUNTIME,
                registration.matchPathRuleToCached("/api/{id}"));
    }

    @Test
    void matchPathRuleToCached_onlyExcludeNoInclude_returnsAlwaysWhenDisjoint() {
        // no includePatterns, exclude=/admin, pathRule=/api/users 无交集 → ALWAYS
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        registration.excludePathPatterns("/admin/**");
        assertEquals(ContainmentResult.ALWAYS,
                registration.matchPathRuleToCached("/api/users"));
    }

    @Test
    void pathMatcher_acceptsCustomMatcher() {
        WebFilterRegistration registration = new WebFilterRegistration(mock(WebFilter.class));
        AntPathMatcher matcher = new AntPathMatcher();
        assertSame(registration, registration.pathMatcher(matcher));
    }
}