package org.springframework.web.servlet.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.context.request.WebRequestInterceptor;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MappedInterceptorTest {

    @Mock
    HandlerInterceptor delegate;
    @Mock
    HttpServletRequest request;
    @Mock
    HttpServletResponse response;
    @Mock
    ModelAndView modelAndView;

    private PathMatcher pathMatcher = new AntPathMatcher();

    @Test
    void constructor_withIncludePatterns_setsPatterns() {
        MappedInterceptor mi = new MappedInterceptor(new String[]{"/api/**"}, delegate);
        assertArrayEquals(new String[]{"/api/**"}, mi.getIncludePatterns());
        assertNull(mi.getExcludePatterns());
        assertSame(delegate, mi.getInterceptor());
    }

    @Test
    void constructor_withExcludePatterns_setsExcludes() {
        MappedInterceptor mi = new MappedInterceptor(
                new String[]{"/api/**"}, new String[]{"/api/public/**"}, delegate);
        assertArrayEquals(new String[]{"/api/public/**"}, mi.getExcludePatterns());
    }

    @Test
    void constructor_withWebRequestInterceptor_wrapsInAdapter() {
        MappedInterceptor mi = new MappedInterceptor(
                new String[]{"/**"}, new WebRequestInterceptor() {
                    @Override public void preHandle(org.springframework.web.context.request.WebRequest request) {}
                    @Override public void postHandle(org.springframework.web.context.request.WebRequest request, org.springframework.ui.ModelMap model) {}
                    @Override public void afterCompletion(org.springframework.web.context.request.WebRequest request, Exception ex) {}
                });
        assertNotNull(mi.getInterceptor());
    }

    @Test
    void matches_emptyIncludes_matchAll() {
        MappedInterceptor mi = new MappedInterceptor(null, delegate);
        assertTrue(mi.matches("/any/path", pathMatcher));
    }

    @Test
    void matches_emptyArrayIncludes_matchAll() {
        MappedInterceptor mi = new MappedInterceptor(new String[0], delegate);
        assertTrue(mi.matches("/any/path", pathMatcher));
    }

    @Test
    void matches_matchingInclude_returnsTrue() {
        MappedInterceptor mi = new MappedInterceptor(
                new String[]{"/api/**"}, delegate);
        assertTrue(mi.matches("/api/users", pathMatcher));
    }

    @Test
    void matches_nonMatchingInclude_returnsFalse() {
        MappedInterceptor mi = new MappedInterceptor(
                new String[]{"/api/**"}, delegate);
        assertFalse(mi.matches("/admin", pathMatcher));
    }

    @Test
    void matches_excludeOverridesInclude_returnsFalse() {
        MappedInterceptor mi = new MappedInterceptor(
                new String[]{"/api/**"}, new String[]{"/api/public/**"}, delegate);
        assertFalse(mi.matches("/api/public/login", pathMatcher));
    }

    @Test
    void matches_matchingIncludeWithExclude_returnsTrue() {
        MappedInterceptor mi = new MappedInterceptor(
                new String[]{"/api/**"}, new String[]{"/api/public/**"}, delegate);
        assertTrue(mi.matches("/api/private/data", pathMatcher));
    }

    @Test
    void matches_usesOwnPathMatcher() {
        PathMatcher customMatcher = mock(PathMatcher.class);
        when(customMatcher.match("/custom/**", "/test")).thenReturn(true);

        MappedInterceptor mi = new MappedInterceptor(
                new String[]{"/custom/**"}, delegate);
        mi.setPathMatcher(customMatcher);

        assertTrue(mi.matches("/test", pathMatcher));
        verify(customMatcher).match("/custom/**", "/test");
    }

    @Test
    void getPathPatterns_returnsIncludePatterns() {
        MappedInterceptor mi = new MappedInterceptor(
                new String[]{"/api/**"}, delegate);
        assertArrayEquals(new String[]{"/api/**"}, mi.getPathPatterns());
    }

    @Test
    void getPathMatcher_defaultIsNull() {
        MappedInterceptor mi = new MappedInterceptor(null, delegate);
        assertNull(mi.getPathMatcher());
    }

    @Test
    void setPathMatcher_storesMatcher() {
        PathMatcher custom = new AntPathMatcher();
        MappedInterceptor mi = new MappedInterceptor(null, delegate);
        mi.setPathMatcher(custom);
        assertSame(custom, mi.getPathMatcher());
    }

    @Test
    void preHandle_delegatesToInterceptor() throws Exception {
        MappedInterceptor mi = new MappedInterceptor(null, delegate);
        when(delegate.preHandle(request, response, "handler")).thenReturn(true);

        boolean result = mi.preHandle(request, response, "handler");

        assertTrue(result);
        verify(delegate).preHandle(request, response, "handler");
    }

    @Test
    void postHandle_delegatesToInterceptor() throws Exception {
        MappedInterceptor mi = new MappedInterceptor(null, delegate);

        mi.postHandle(request, response, "handler", modelAndView);

        verify(delegate).postHandle(request, response, "handler", modelAndView);
    }

    @Test
    void afterCompletion_delegatesToInterceptor() throws Exception {
        MappedInterceptor mi = new MappedInterceptor(null, delegate);
        Exception ex = new Exception("test");

        mi.afterCompletion(request, response, "handler", ex);

        verify(delegate).afterCompletion(request, response, "handler", ex);
    }
}