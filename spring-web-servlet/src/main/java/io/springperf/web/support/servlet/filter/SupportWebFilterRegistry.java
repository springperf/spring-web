package io.springperf.web.support.servlet.filter;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.filter.WebFilterRegistration;
import io.springperf.web.core.filter.WebFilterRegistry;
import io.springperf.web.support.servlet.context.DynamicFilterRegistration;
import io.springperf.web.support.servlet.context.PerfServletContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.List;

@Slf4j
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

    @Override
    public void initComponentPhase3() throws Exception {
        super.initComponentPhase3();
        appendDynamicFilterRegistrations();
    }

    /**
     * 认领 ServletContext 初始化窗口内 {@code addFilter} 的登记项，包装后追加到 filter 链尾。
     * <p>
     * 必须在 {@code super.initComponentPhase3()} 之后执行：父类会清空并重建 {@code registrations}，先追加会被冲掉。
     * </p>
     * <p>
     * 组件名用注册名本身：它同时是 {@code FilterConfig#getFilterName()} 的返回值，规范要求返回注册名（加前缀会污染 用户代码里的按名查找）。动态 filter 的 wrapper
     * 不进容器，不参与容器的按名去重。
     * </p>
     */
    private void appendDynamicFilterRegistrations() {
        PerfServletContext servletContext = getWebContext().getWebComponent(PerfServletContext.class);
        if (servletContext == null) {
            return;
        }
        List<DynamicFilterRegistration> dynamicRegistrations = servletContext.claimFilterRegistrations();
        if (dynamicRegistrations.isEmpty()) {
            return;
        }
        int appended = 0;
        for (DynamicFilterRegistration dynamic : dynamicRegistrations) {
            if (dynamic.getUrlPatternMappings().isEmpty()) {
                // 规范允许先 addFilter 再 addMapping；但窗口结束时仍无映射，这个 filter 永远不会被调用
                log.warn("Filter '{}' has no url-pattern mapping, it will not be applied to any request",
                        dynamic.getName());
                continue;
            }
            FilterWrapper wrapper = new FilterWrapper(dynamic.getFilter(), dynamic.getName(),
                    dynamic.getInitParameters());
            // 动态 filter 的 init-param 只有走 init 才能生效（bean filter 的 init 由框架另行处理）
            wrapper.initWithWebContext(getWebContext());
            WebFilterRegistration registration = wrapFilterToRegistration(wrapper);
            registration.addPathPatterns(dynamic.getUrlPatternMappings().toArray(new String[0]));
            registrations.add(registration);
            appended++;
        }
        if (appended > 0) {
            initAllFilters();
            log.info("Appended {} dynamically registered filter(s) to the filter chain", appended);
        }
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
