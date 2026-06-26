package io.springperf.web.filter;

import io.springperf.web.context.WebComponent;
import io.springperf.web.util.PathPatternUtils;
import io.springperf.web.util.support.ContainmentResult;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.PathMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WebFilterRegistration implements WebComponent {

    private final WebFilter filter;

    private final List<String> includePatterns = new ArrayList<>();

    private final List<String> excludePatterns = new ArrayList<>();

    @Nullable
    private PathMatcher pathMatcher;

    private int order = 0;


    /**
     * Create a {@link WebFilterRegistration} instance.
     */
    public WebFilterRegistration(WebFilter filter) {
        Assert.notNull(filter, "WebFilter is required");
        this.filter = filter;
    }

    @Override
    public String getComponentName() {
        return this.filter.getComponentName();
    }


    /**
     * Add URL patterns to which the registered filter should apply to.
     */
    public WebFilterRegistration addPathPatterns(String... patterns) {
        return addPathPatterns(Arrays.asList(patterns));
    }

    /**
     * List-based variant of {@link #addPathPatterns(String...)}.
     */
    public WebFilterRegistration addPathPatterns(List<String> patterns) {
        this.includePatterns.addAll(patterns);
        return this;
    }

    /**
     * Add URL patterns to which the registered filter should not apply to.
     */
    public WebFilterRegistration excludePathPatterns(String... patterns) {
        return excludePathPatterns(Arrays.asList(patterns));
    }

    /**
     * List-based variant of {@link #excludePathPatterns(String...)}.
     */
    public WebFilterRegistration excludePathPatterns(List<String> patterns) {
        this.excludePatterns.addAll(patterns);
        return this;
    }

    /**
     * A PathMatcher implementation to use with this filter. This is an optional,
     * advanced property required only if using custom PathMatcher implementations
     * that support mapping metadata other than the Ant path patterns supported
     * by default.
     */
    public WebFilterRegistration pathMatcher(PathMatcher pathMatcher) {
        this.pathMatcher = pathMatcher;
        return this;
    }

    /**
     * Specify an order position to be used. Default is 0.
     */
    public WebFilterRegistration order(int order) {
        this.order = order;
        return this;
    }

    /**
     * Return the order position to be used.
     */
    public int getOrder() {
        return this.order;
    }

    protected WebFilter getFilter() {
        return this.filter;
    }

    protected List<String> getIncludePatterns() {
        return this.includePatterns;
    }

    protected List<String> getExcludePatterns() {
        return this.excludePatterns;
    }

    @Nullable
    protected PathMatcher getPathMatcher() {
        return this.pathMatcher;
    }

    protected ContainmentResult matchPathRuleToCached(String pathRule) {
        return PathPatternUtils.matchPathRuleToCached(this.includePatterns, this.excludePatterns, pathRule);
    }

    /**
     * Runtime path matching: check if this filter should apply to the given request path.
     * Uses {@link PathMatcher} (AntPathMatcher by default) for path pattern matching.
     *
     * @param lookupPath the actual request path
     * @return {@code true} if this filter applies
     */
    public boolean matches(String lookupPath) {
        PathMatcher pathMatcherToUse = this.pathMatcher != null ? this.pathMatcher : PathPatternUtils.getMatcher();
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
}
