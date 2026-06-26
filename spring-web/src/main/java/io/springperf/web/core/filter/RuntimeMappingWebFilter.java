package io.springperf.web.core.filter;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.util.ServletFilterPatternUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;

public class RuntimeMappingWebFilter implements WebFilter {

    private final WebFilter delegate;

    @Nullable
    private final String[] includePatterns;

    @Nullable
    private final String[] excludePatterns;

    private final int order;

    public RuntimeMappingWebFilter(WebFilter filter, String[] includePatterns, String[] excludePatterns,
                                    int order) {
        this.delegate = filter;
        this.includePatterns = includePatterns;
        this.excludePatterns = excludePatterns;
        this.order = order;
    }

    /**
     * Runtime-only constructor; order defaults to 0.
     */
    public RuntimeMappingWebFilter(WebFilter filter, String[] includePatterns, String[] excludePatterns) {
        this(filter, includePatterns, excludePatterns, 0);
    }

    /**
     * Convenience constructor from a WebFilterRegistration with RUNTIME result.
     */
    public RuntimeMappingWebFilter(WebFilterRegistration registration) {
        this(registration.getFilter(),
                registration.getIncludePatterns().toArray(new String[0]),
                registration.getExcludePatterns().toArray(new String[0]),
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
     * Determine a match for the given lookup path using Servlet 规范路径匹配。
     *
     * @param lookupPath the current request path
     * @return {@code true} if the filter applies to the given request path
     */
    public boolean matches(String lookupPath) {
        if (!ObjectUtils.isEmpty(this.excludePatterns)) {
            for (String pattern : this.excludePatterns) {
                if (ServletFilterPatternUtils.matches(pattern, lookupPath)) {
                    return false;
                }
            }
        }
        if (ObjectUtils.isEmpty(this.includePatterns)) {
            return true;
        }
        for (String pattern : this.includePatterns) {
            if (ServletFilterPatternUtils.matches(pattern, lookupPath)) {
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
