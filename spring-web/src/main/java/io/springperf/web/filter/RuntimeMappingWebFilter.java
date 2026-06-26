package io.springperf.web.filter;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.util.PathPatternUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.util.PathMatcher;

public class RuntimeMappingWebFilter implements WebFilter {

    private final WebFilter delegate;

    @Nullable
    private final String[] includePatterns;

    @Nullable
    private final String[] excludePatterns;

    @Nullable
    private PathMatcher pathMatcher;

    private final int order;

    public RuntimeMappingWebFilter(WebFilter filter, String[] includePatterns, String[] excludePatterns,
                                    @Nullable PathMatcher pathMatcher, int order) {
        this.delegate = filter;
        this.includePatterns = includePatterns;
        this.excludePatterns = excludePatterns;
        this.pathMatcher = pathMatcher;
        this.order = order;
    }

    /**
     * Runtime-only constructor; pathMatcher will use the default from PathPatternUtils.
     */
    public RuntimeMappingWebFilter(WebFilter filter, String[] includePatterns, String[] excludePatterns) {
        this(filter, includePatterns, excludePatterns, null, 0);
    }

    /**
     * Convenience constructor from a WebFilterRegistration with RUNTIME result.
     */
    public RuntimeMappingWebFilter(WebFilterRegistration registration) {
        this(registration.getFilter(),
                registration.getIncludePatterns().toArray(new String[0]),
                registration.getExcludePatterns().toArray(new String[0]),
                registration.getPathMatcher(),
                registration.getOrder());
    }

    public WebFilter getDelegate() {
        return this.delegate;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    /**
     * Determine a match for the given lookup path.
     *
     * @param lookupPath the current request path
     * @return {@code true} if the filter applies to the given request path
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
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
        if (matches(request.getPath())) {
            delegate.doFilter(request, response, chain);
        } else {
            chain.doFilter(request, response);
        }
    }
}
