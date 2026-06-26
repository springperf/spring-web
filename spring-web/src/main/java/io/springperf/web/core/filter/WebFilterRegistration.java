package io.springperf.web.core.filter;

import io.springperf.web.context.WebComponent;
import io.springperf.web.util.ServletFilterPatternUtils;
import io.springperf.web.util.support.ContainmentResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class WebFilterRegistration implements WebComponent {

    private final WebFilter filter;

    private final List<String> includePatterns = new ArrayList<>();

    private final List<String> excludePatterns = new ArrayList<>();

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
        validatePatterns(patterns);
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
        validatePatterns(patterns);
        this.excludePatterns.addAll(patterns);
        return this;
    }

    private static void validatePatterns(List<String> patterns) {
        for (String pattern : patterns) {
            String msg = ServletFilterPatternUtils.validateServletPattern(pattern);
            if (msg != null) {
                log.warn("{}", msg);
            }
        }
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

    protected ContainmentResult matchPathRuleToCached(String pathRule) {
        return ServletFilterPatternUtils.matchPathRuleToCached(this.includePatterns, this.excludePatterns, pathRule);
    }

    /**
     * Runtime path matching: check if this filter should apply to the given request path.
     * Uses Servlet 规范路径匹配语义。
     *
     * @param lookupPath the actual request path
     * @return {@code true} if this filter applies
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
}
