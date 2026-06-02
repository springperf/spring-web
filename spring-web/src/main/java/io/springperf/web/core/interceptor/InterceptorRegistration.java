package io.springperf.web.core.interceptor;

import io.springperf.web.context.WebComponent;
import io.springperf.web.util.PathPatternUtils;
import io.springperf.web.util.support.ContainmentResult;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.PathMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InterceptorRegistration implements WebComponent {

    private final HandlerInterceptor interceptor;

    private final List<String> includePatterns = new ArrayList<>();

    private final List<String> excludePatterns = new ArrayList<>();

    @Nullable
    private PathMatcher pathMatcher;

    private int order = 0;


    /**
     * Create an {@link InterceptorRegistration} instance.
     */
    public InterceptorRegistration(HandlerInterceptor interceptor) {
        Assert.notNull(interceptor, "Interceptor is required");
        this.interceptor = interceptor;
    }


    /**
     * Add URL patterns to which the registered interceptor should apply to.
     */
    public InterceptorRegistration addPathPatterns(String... patterns) {
        return addPathPatterns(Arrays.asList(patterns));
    }

    /**
     * List-based variant of {@link #addPathPatterns(String...)}.
     *
     * @since 5.0.3
     */
    public InterceptorRegistration addPathPatterns(List<String> patterns) {
        this.includePatterns.addAll(patterns);
        return this;
    }

    /**
     * Add URL patterns to which the registered interceptor should not apply to.
     */
    public InterceptorRegistration excludePathPatterns(String... patterns) {
        return excludePathPatterns(Arrays.asList(patterns));
    }

    /**
     * List-based variant of {@link #excludePathPatterns(String...)}.
     *
     * @since 5.0.3
     */
    public InterceptorRegistration excludePathPatterns(List<String> patterns) {
        this.excludePatterns.addAll(patterns);
        return this;
    }

    /**
     * A PathMatcher implementation to use with this interceptor. This is an optional,
     * advanced property required only if using custom PathMatcher implementations
     * that support mapping metadata other than the Ant path patterns supported
     * by default.
     */
    public InterceptorRegistration pathMatcher(PathMatcher pathMatcher) {
        this.pathMatcher = pathMatcher;
        return this;
    }

    /**
     * Specify an order position to be used. Default is 0.
     *
     * @since 4.3.23
     */
    public InterceptorRegistration order(int order) {
        this.order = order;
        return this;
    }

    /**
     * Return the order position to be used.
     */
    public int getOrder() {
        return this.order;
    }

    protected HandlerInterceptor getInterceptor() {
        return this.interceptor;
    }

    protected List<String> getIncludePatterns() {
        return this.includePatterns;
    }

    protected List<String> getExcludePatterns() {
        return this.excludePatterns;
    }

    protected PathMatcher getPathMatcher() {
        return this.pathMatcher;
    }

    protected ContainmentResult matchPathRuleToCached(String pathRule) {
        if (this.includePatterns.isEmpty() && this.excludePatterns.isEmpty()) {
            return ContainmentResult.ALWAYS;
        }
        ContainmentResult includeResult = PathPatternUtils.patternListContains(includePatterns, pathRule);
        if (this.includePatterns.isEmpty()) {
            includeResult = ContainmentResult.ALWAYS;
        }
        if (includeResult == ContainmentResult.NEVER) {
            // mapping 永远不可能命中 include
            return ContainmentResult.NEVER;
        }

        ContainmentResult excludeResult = PathPatternUtils.patternListContains(excludePatterns, pathRule);
        if (excludeResult == ContainmentResult.ALWAYS) {
            // mapping 必然被 exclude
            return ContainmentResult.NEVER;
        }
        if (includeResult == ContainmentResult.ALWAYS
                && excludeResult == ContainmentResult.NEVER) {
            return ContainmentResult.ALWAYS;
        }
        return ContainmentResult.RUNTIME;
    }


}
