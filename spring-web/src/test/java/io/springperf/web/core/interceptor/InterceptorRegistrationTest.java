package io.springperf.web.core.interceptor;

import io.springperf.web.util.support.ContainmentResult;
import org.junit.jupiter.api.Test;
import org.springframework.util.PathMatcher;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InterceptorRegistrationTest {

    private final HandlerInterceptor interceptor = new HandlerInterceptor() {};

    @Test
    void constructor_storesInterceptor() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        assertSame(interceptor, registration.getInterceptor());
    }

    @Test
    void constructor_nullInterceptor_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new InterceptorRegistration(null));
    }

    @Test
    void constructor_defaultOrder() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        assertEquals(0, registration.getOrder());
    }

    @Test
    void addPathPatterns_varargs_addsPatterns() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        registration.addPathPatterns("/api/**", "/admin/**");

        assertEquals(Arrays.asList("/api/**", "/admin/**"), registration.getIncludePatterns());
    }

    @Test
    void addPathPatterns_list_addsPatterns() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        registration.addPathPatterns(Arrays.asList("/api/**", "/admin/**"));

        assertEquals(Arrays.asList("/api/**", "/admin/**"), registration.getIncludePatterns());
    }

    @Test
    void addPathPatterns_returnsThis() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        assertSame(registration, registration.addPathPatterns("/api/**"));
    }

    @Test
    void addPathPatterns_calledMultipleTimes_appendsPatterns() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        registration.addPathPatterns("/api/**");
        registration.addPathPatterns("/admin/**");

        assertEquals(Arrays.asList("/api/**", "/admin/**"), registration.getIncludePatterns());
    }

    @Test
    void excludePathPatterns_varargs_addsPatterns() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        registration.excludePathPatterns("/public/**", "/static/**");

        assertEquals(Arrays.asList("/public/**", "/static/**"), registration.getExcludePatterns());
    }

    @Test
    void excludePathPatterns_list_addsPatterns() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        registration.excludePathPatterns(Arrays.asList("/public/**", "/static/**"));

        assertEquals(Arrays.asList("/public/**", "/static/**"), registration.getExcludePatterns());
    }

    @Test
    void excludePathPatterns_returnsThis() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        assertSame(registration, registration.excludePathPatterns("/public/**"));
    }

    @Test
    void pathMatcher_setsPathMatcher() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);
        PathMatcher pathMatcher = mock(PathMatcher.class);

        registration.pathMatcher(pathMatcher);

        assertSame(pathMatcher, registration.getPathMatcher());
    }

    @Test
    void pathMatcher_returnsThis() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        assertSame(registration, registration.pathMatcher(mock(PathMatcher.class)));
    }

    @Test
    void order_setsOrder() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        registration.order(42);

        assertEquals(42, registration.getOrder());
    }

    @Test
    void order_returnsThis() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        assertSame(registration, registration.order(42));
    }

    // ---- matchPathRuleToCached ----

    @Test
    void matchPathRuleToCached_noPatterns_returnsAlways() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);

        assertEquals(ContainmentResult.ALWAYS, registration.matchPathRuleToCached("/any/path"));
    }

    @Test
    void matchPathRuleToCached_includeMatches_returnsAlways() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);
        registration.addPathPatterns("/api/**");

        assertEquals(ContainmentResult.ALWAYS, registration.matchPathRuleToCached("/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeNeverMatches_returnsNever() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);
        registration.addPathPatterns("/api/**");

        assertEquals(ContainmentResult.NEVER, registration.matchPathRuleToCached("/admin/users"));
    }

    @Test
    void matchPathRuleToCached_excludeAlways_returnsNever() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);
        registration.addPathPatterns("/**");
        registration.excludePathPatterns("/api/**");

        assertEquals(ContainmentResult.NEVER, registration.matchPathRuleToCached("/api/users"));
    }

    @Test
    void matchPathRuleToCached_includeAlwaysExcludeNever_returnsAlways() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);
        registration.addPathPatterns("/**");
        registration.excludePathPatterns("/admin/**");

        assertEquals(ContainmentResult.ALWAYS, registration.matchPathRuleToCached("/api/users"));
    }

    @Test
    void matchPathRuleToCached_singleWildcard_returnsAlways() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);
        registration.addPathPatterns("/api/*");

        assertEquals(ContainmentResult.ALWAYS, registration.matchPathRuleToCached("/api/users"));
    }

    @Test
    void matchPathRuleToCached_excludePartialIncludeRuntime_returnsNever() {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);
        registration.addPathPatterns("/api/*");
        registration.excludePathPatterns("/api/**");

        assertEquals(ContainmentResult.NEVER, registration.matchPathRuleToCached("/api/users"));
    }
}