package io.springperf.web.core.interceptor;

import io.springperf.web.util.PathPatternUtils;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.util.PathMatcher;

public class RuntimeMappingInterceptor implements HandlerInterceptor {

    @Nullable
    private final String[] includePatterns;

    @Nullable
    private final String[] excludePatterns;

    private final HandlerInterceptor interceptor;

    @Nullable
    private PathMatcher pathMatcher;

    public RuntimeMappingInterceptor(@Nullable String[] includePatterns, @Nullable String[] excludePatterns, HandlerInterceptor interceptor) {
        this(includePatterns, excludePatterns, interceptor, null);
    }

    public RuntimeMappingInterceptor(@Nullable String[] includePatterns, @Nullable String[] excludePatterns, HandlerInterceptor interceptor, @Nullable PathMatcher pathMatcher) {
        this.includePatterns = includePatterns;
        this.excludePatterns = excludePatterns;
        this.interceptor = interceptor;
        this.pathMatcher = pathMatcher;
    }

    /**
     * The configured PathMatcher, or {@code null} if none.
     */
    @Nullable
    public PathMatcher getPathMatcher() {
        return this.pathMatcher;
    }

    /**
     * The path into the application the interceptor is mapped to.
     */
    @Nullable
    public String[] getPathPatterns() {
        return this.includePatterns;
    }

    /**
     * The actual {@link HandlerInterceptor} reference.
     */
    public HandlerInterceptor getInterceptor() {
        return this.interceptor;
    }


    /**
     * Determine a match for the given lookup path.
     *
     * @param lookupPath the current request path
     * @return {@code true} if the interceptor applies to the given request path
     */
    public boolean matches(String lookupPath) {
        PathMatcher pathMatcherToUse = this.pathMatcher == null ? PathPatternUtils.getMatcher() : this.pathMatcher;
        if (!ObjectUtils.isEmpty(this.excludePatterns)) {
            for (String pattern : this.excludePatterns) {
                if (pathMatcherToUse.match(pattern, lookupPath)) {
                    return false;
                }
            }
        }
        if (ObjectUtils.isEmpty(this.includePatterns)) {
            return true;
        }
        for (String pattern : this.includePatterns) {
            if (pathMatcherToUse.match(pattern, lookupPath)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean preHandle(ServerHttpRequest request, ServerHttpResponse response, Object handler) throws Exception {
        return interceptor.preHandle(request, response, handler);
    }

    @Override
    public void postHandle(ServerHttpRequest request, ServerHttpResponse response, Object handler, Object result) throws Exception {
        interceptor.postHandle(request, response, handler, result);
    }

    @Override
    public void afterCompletion(ServerHttpRequest request, ServerHttpResponse response, Object handler, Throwable ex) throws Exception {
        interceptor.afterCompletion(request, response, handler, ex);
    }

    @Override
    public void afterConcurrentHandlingStarted(ServerHttpRequest request, ServerHttpResponse response, Object handler) throws Exception {
        interceptor.afterConcurrentHandlingStarted(request, response, handler);
    }
}
