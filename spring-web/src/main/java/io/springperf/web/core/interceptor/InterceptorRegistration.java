package io.springperf.web.core.interceptor;

import io.springperf.web.context.WebComponent;
import io.springperf.web.util.PathPatternUtils;
import io.springperf.web.util.support.ContainmentResult;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.PathMatcher;
import org.springframework.web.method.ControllerAdviceBean;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InterceptorRegistration implements WebComponent {

    private final HandlerInterceptor interceptor;

    @Nullable
    private String componentName;

    private final List<String> includePatterns = new ArrayList<>();

    private final List<String> excludePatterns = new ArrayList<>();

    @Nullable
    private PathMatcher pathMatcher;

    /**
     * 类级匹配载体（ControllerAdvice 语义）。非空时该拦截器为"方法级/类级"匹配：
     * 经 {@link #matchesControllerType} 按 handler 的 beanType 判定，而非 path 维度。
     */
    @Nullable
    private ControllerAdviceBean controllerAdvice;

    private int order = 0;


    /**
     * Create an {@link InterceptorRegistration} instance.
     */
    public InterceptorRegistration(HandlerInterceptor interceptor) {
        Assert.notNull(interceptor, "Interceptor is required");
        this.interceptor = interceptor;
    }


    /**
     * 自定义组件名。默认取被包装拦截器的实现类全名 + 实例标记（保证多拦截器注册时不冲突，
     * 即使多个拦截器被包装为同一适配类（如 {@code HandlerInterceptorWrapper}））。
     */
    public InterceptorRegistration componentName(String componentName) {
        this.componentName = componentName;
        return this;
    }

    @Override
    public String getComponentName() {
        if (this.componentName != null) {
            return this.componentName;
        }
        Class<?> type = this.interceptor.getClass();
        return type.getName() + "@" + Integer.toHexString(System.identityHashCode(this.interceptor));
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
     * 声明为"方法级/类级"拦截器：按 handler 的 controller 类型（ControllerAdvice 语义）
     * 匹配，而非 URL path。设置后 {@link #matchPathRuleToCached} 不再参与匹配。
     *
     * @param controllerAdvice ControllerAdvice 匹配载体（含 assignableTypes/annotations/basePackages 约束）
     */
    public InterceptorRegistration applyTo(ControllerAdviceBean controllerAdvice) {
        Assert.notNull(controllerAdvice, "ControllerAdvice is required");
        this.controllerAdvice = controllerAdvice;
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

    protected boolean isControllerAdviceScoped() {
        return this.controllerAdvice != null;
    }

    /**
     * 类级匹配：给定 handler 的 controller 类型是否命中 ControllerAdvice 约束。
     *
     * @param beanType handler 的 controller bean 类型
     */
    protected boolean matchesControllerType(Class<?> beanType) {
        return this.controllerAdvice != null && beanType != null
                && this.controllerAdvice.isApplicableToBeanType(beanType);
    }

    protected ContainmentResult matchPathRuleToCached(String pathRule) {
        return PathPatternUtils.matchPathRuleToCached(this.includePatterns, this.excludePatterns, pathRule);
    }


}
