package org.springframework.web.servlet.config.annotation;

import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import static org.junit.jupiter.api.Assertions.*;

class InterceptorRegistrationTest {

    private final HandlerInterceptor interceptor = new HandlerInterceptor() {};

    @Test
    void constructor_nullInterceptor_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new InterceptorRegistration(null));
    }

    @Test
    void getInterceptor_returnsInterceptor() {
        InterceptorRegistration reg = new InterceptorRegistration(interceptor);
        assertSame(interceptor, reg.getInterceptor());
    }

    @Test
    void addPathPatterns_addsPatterns() {
        InterceptorRegistration reg = new InterceptorRegistration(interceptor)
                .addPathPatterns("/api/**", "/admin/**");

        assertEquals(2, reg.getIncludePatterns().size());
        assertTrue(reg.getIncludePatterns().contains("/api/**"));
    }

    @Test
    void addPathPatterns_listVariant_addsPatterns() {
        InterceptorRegistration reg = new InterceptorRegistration(interceptor)
                .addPathPatterns(java.util.Arrays.asList("/api/**"));

        assertEquals(1, reg.getIncludePatterns().size());
    }

    @Test
    void excludePathPatterns_addsExcludes() {
        InterceptorRegistration reg = new InterceptorRegistration(interceptor)
                .excludePathPatterns("/api/public/**");

        assertEquals(1, reg.getExcludePatterns().size());
        assertTrue(reg.getExcludePatterns().contains("/api/public/**"));
    }

    @Test
    void excludePathPatterns_listVariant_addsExcludes() {
        InterceptorRegistration reg = new InterceptorRegistration(interceptor)
                .excludePathPatterns(java.util.Arrays.asList("/api/public/**"));

        assertEquals(1, reg.getExcludePatterns().size());
    }

    @Test
    void pathMatcher_setsPathMatcher() {
        PathMatcher matcher = new AntPathMatcher();
        InterceptorRegistration reg = new InterceptorRegistration(interceptor)
                .pathMatcher(matcher);

        assertSame(matcher, reg.getPathMatcher());
    }

    @Test
    void order_setsOrder() {
        InterceptorRegistration reg = new InterceptorRegistration(interceptor)
                .order(5);

        assertEquals(5, reg.getOrder());
    }

    @Test
    void order_defaultIsZero() {
        InterceptorRegistration reg = new InterceptorRegistration(interceptor);
        assertEquals(0, reg.getOrder());
    }

    @Test
    void chainedCalls_returnsSelf() {
        InterceptorRegistration reg = new InterceptorRegistration(interceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/public/**")
                .order(1);

        assertNotNull(reg);
        assertEquals(1, reg.getIncludePatterns().size());
        assertEquals(1, reg.getExcludePatterns().size());
        assertEquals(1, reg.getOrder());
    }

    @Test
    void getPathMatcher_defaultIsNull() {
        InterceptorRegistration reg = new InterceptorRegistration(interceptor);
        assertNull(reg.getPathMatcher());
    }
}