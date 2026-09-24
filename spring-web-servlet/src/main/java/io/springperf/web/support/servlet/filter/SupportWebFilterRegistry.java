package io.springperf.web.support.servlet.filter;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.filter.WebFilterRegistration;
import io.springperf.web.core.filter.WebFilterRegistry;
import org.springframework.core.annotation.AnnotatedElementUtils;

public class SupportWebFilterRegistry extends WebFilterRegistry {

    public SupportWebFilterRegistry(DispatcherHandler dispatcherHandler) {
        super(dispatcherHandler);
        autoRegisterWebComponent(jakarta.servlet.Filter.class, filter -> {
            WebFilterRegistration registration = wrapFilterToRegistration(new FilterWrapper(filter));
            String[] patterns = resolveUrlPatterns(filter);
            if (patterns.length > 0) {
                registration.addPathPatterns(patterns);
            }
            return registration;
        });
    }

    /**
     * 解析 {@link jakarta.servlet.annotation.WebFilter} 的 {@code urlPatterns}/{@code value}， 使其路径限制对框架 {@link WebFilter}
     * 生效（框架 WebFilter 默认全路径生效）。
     * <p>
     * 使用 {@link AnnotatedElementUtils#findMergedAnnotation} 读取注解：被 AOP/CGLIB 代理或经 子类继承（非 {@code @Inherited}）的
     * Filter，{@code getClass().getAnnotation} 取不到注解会被 静默升格为全局 Filter；findMergedAnnotation 会沿超类/接口查找，与同模块
     * {@code FilterWrapper} 解析 initParams 的策略保持一致。
     * </p>
     */
    static String[] resolveUrlPatterns(jakarta.servlet.Filter filter) {
        jakarta.servlet.annotation.WebFilter webFilter = AnnotatedElementUtils.findMergedAnnotation(filter.getClass(),
                jakarta.servlet.annotation.WebFilter.class);
        if (webFilter == null) {
            return new String[0];
        }
        String[] urlPatterns = webFilter.urlPatterns();
        if (urlPatterns.length == 0) {
            urlPatterns = webFilter.value();
        }
        return urlPatterns;
    }
}
