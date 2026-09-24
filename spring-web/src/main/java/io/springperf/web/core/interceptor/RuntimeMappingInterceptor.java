package io.springperf.web.core.interceptor;

import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.util.PathMatcher;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.util.PathPatternUtils;

public class RuntimeMappingInterceptor implements HandlerInterceptor {

    @Nullable
    private final String[] includePatterns;

    @Nullable
    private final String[] excludePatterns;

    private final HandlerInterceptor interceptor;

    @Nullable
    private PathMatcher pathMatcher;

    public RuntimeMappingInterceptor(@Nullable String[] includePatterns, @Nullable String[] excludePatterns,
            HandlerInterceptor interceptor) {
        this(includePatterns, excludePatterns, interceptor, null);
    }

    public RuntimeMappingInterceptor(@Nullable String[] includePatterns, @Nullable String[] excludePatterns,
            HandlerInterceptor interceptor, @Nullable PathMatcher pathMatcher) {
        // 防御性拷贝：配置数组由调用方持有，直接存引用会让外部修改影响运行期路由
        this.includePatterns = includePatterns != null ? includePatterns.clone() : null;
        this.excludePatterns = excludePatterns != null ? excludePatterns.clone() : null;
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
        // 返回副本，避免调用方经由返回数组改动内部配置
        return this.includePatterns != null ? this.includePatterns.clone() : null;
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
     * @param lookupPath
     *            the current request path
     *
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
    public boolean preHandle(WebServerHttpRequest request, WebServerHttpResponse response, Object handler)
            throws Exception {
        return interceptor.preHandle(request, response, handler);
    }

    @Override
    public void postHandle(WebServerHttpRequest request, WebServerHttpResponse response, Object handler, Object result)
            throws Exception {
        interceptor.postHandle(request, response, handler, result);
    }

    @Override
    public void afterCompletion(WebServerHttpRequest request, WebServerHttpResponse response, Object handler,
            Throwable ex) throws Exception {
        interceptor.afterCompletion(request, response, handler, ex);
    }

    @Override
    public void afterConcurrentHandlingStarted(WebServerHttpRequest request, WebServerHttpResponse response,
            Object handler) throws Exception {
        interceptor.afterConcurrentHandlingStarted(request, response, handler);
    }
}
