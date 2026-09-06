package org.springframework.web.servlet.config.annotation;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.PathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InterceptorRegistration {

    private final HandlerInterceptor interceptor;

    private final List<String> includePatterns = new ArrayList<>();

    private final List<String> excludePatterns = new ArrayList<>();

    @Nullable
    private PathMatcher pathMatcher;

    private int order = 0;

    /** 用户是否显式调用过 {@link #order(int)}（区分默认 order=0 与显式 order(0)）。 */
    private boolean orderSet = false;


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
        this.orderSet = true;
        return this;
    }

    /**
     * Return the order position to be used.
     */
    public int getOrder() {
        return this.order;
    }

    /**
     * 用户是否显式设置过 order（{@code true} 表示 {@code order()} 被调用过；{@code false}
     * 表示使用默认 order 0，桥接时应按添加顺序分配递增值以保持稳定序）。
     */
    public boolean isOrderSet() {
        return this.orderSet;
    }

    public HandlerInterceptor getInterceptor() {
        return interceptor;
    }

    public List<String> getIncludePatterns() {
        return includePatterns;
    }

    public List<String> getExcludePatterns() {
        return excludePatterns;
    }

    @Nullable
    public PathMatcher getPathMatcher() {
        return pathMatcher;
    }
}