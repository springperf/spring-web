package io.springperf.web.core.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.PathMatcher;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeMappingInterceptorTest {

    private final HandlerInterceptor delegate = new HandlerInterceptor() {};
    private final HandlerInterceptor spyDelegate = spy(new HandlerInterceptor() {});

    @Test
    void constructor_withoutPathMatcher() {
        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(
                new String[]{"/api/**"}, new String[]{"/admin/**"}, delegate);

        assertNull(interceptor.getPathMatcher());
        assertArrayEquals(new String[]{"/api/**"}, interceptor.getPathPatterns());
        assertSame(delegate, interceptor.getInterceptor());
    }

    @Test
    void constructor_withPathMatcher() {
        PathMatcher pathMatcher = mock(PathMatcher.class);
        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(
                new String[]{"/api/**"}, new String[]{"/admin/**"}, delegate, pathMatcher);

        assertSame(pathMatcher, interceptor.getPathMatcher());
    }

    @Test
    void constructor_nullPatterns() {
        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(null, null, delegate);

        assertNull(interceptor.getPathPatterns());
    }

    // ---- matches ----

    @Test
    void matches_noIncludePatterns_returnsTrue() {
        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(null, null, delegate);

        assertTrue(interceptor.matches("/any/path"));
    }

    @Test
    void matches_includeMatches_returnsTrue() {
        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(
                new String[]{"/api/**"}, null, delegate);

        assertTrue(interceptor.matches("/api/users"));
    }

    @Test
    void matches_includeDoesNotMatch_returnsFalse() {
        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(
                new String[]{"/api/**"}, null, delegate);

        assertFalse(interceptor.matches("/admin/users"));
    }

    @Test
    void matches_excludeMatches_returnsFalse() {
        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(
                new String[]{"/**"}, new String[]{"/api/**"}, delegate);

        assertFalse(interceptor.matches("/api/users"));
    }

    @Test
    void matches_excludeTakesPriorityOverInclude() {
        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(
                new String[]{"/api/**"}, new String[]{"/api/users"}, delegate);

        assertFalse(interceptor.matches("/api/users"));
    }

    @Test
    void matches_includeMatchesExcludeDoesNot_returnsTrue() {
        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(
                new String[]{"/api/**"}, new String[]{"/admin/**"}, delegate);

        assertTrue(interceptor.matches("/api/users"));
    }

    @Test
    void matches_customPathMatcher() {
        PathMatcher pathMatcher = mock(PathMatcher.class);
        when(pathMatcher.match("/custom/**", "/my/path")).thenReturn(true);

        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(
                new String[]{"/custom/**"}, null, delegate, pathMatcher);

        assertTrue(interceptor.matches("/my/path"));
        verify(pathMatcher).match("/custom/**", "/my/path");
    }

    @Test
    void matches_excludeWithCustomPathMatcher() {
        PathMatcher pathMatcher = mock(PathMatcher.class);
        when(pathMatcher.match("/exclude/**", "/blocked")).thenReturn(true);

        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(
                null, new String[]{"/exclude/**"}, delegate, pathMatcher);

        assertFalse(interceptor.matches("/blocked"));
    }

    @Test
    void matches_emptyIncludeAndExclude_returnsTrue() {
        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(
                new String[0], new String[0], delegate);

        assertTrue(interceptor.matches("/any/path"));
    }

    // ---- delegation ----

    @Test
    void preHandle_delegatesAndReturnsResult() throws Exception {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Object handler = new Object();
        doReturn(false).when(spyDelegate).preHandle(request, response, handler);

        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(null, null, spyDelegate);

        assertFalse(interceptor.preHandle(request, response, handler));
        verify(spyDelegate).preHandle(request, response, handler);
    }

    @Test
    void postHandle_delegates() throws Exception {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Object handler = new Object();
        Object result = "result";

        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(null, null, spyDelegate);
        interceptor.postHandle(request, response, handler, result);

        verify(spyDelegate).postHandle(request, response, handler, result);
    }

    @Test
    void afterCompletion_delegates() throws Exception {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Object handler = new Object();
        Throwable ex = new RuntimeException("test");

        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(null, null, spyDelegate);
        interceptor.afterCompletion(request, response, handler, ex);

        verify(spyDelegate).afterCompletion(request, response, handler, ex);
    }

    @Test
    void afterConcurrentHandlingStarted_delegates() throws Exception {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Object handler = new Object();

        RuntimeMappingInterceptor interceptor = new RuntimeMappingInterceptor(null, null, spyDelegate);
        interceptor.afterConcurrentHandlingStarted(request, response, handler);

        verify(spyDelegate).afterConcurrentHandlingStarted(request, response, handler);
    }
}