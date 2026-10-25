package io.springperf.web.support.servlet;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.support.servlet.context.DynamicServletRegistration;
import io.springperf.web.support.servlet.context.PerfServletContext;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描 Spring 容器中的 {@link Servlet} Bean，将其注册为框架路由。
 * <p>
 * 复用核心的非控制器路由机制：每个 servlet 包装为 {@link ServletInvoker}， 以 {@link PathMappingContext} 形式注册进
 * {@link MappingRegistry}。servlet 的 url-pattern（来自 {@link WebServlet} 注解）转换为框架的 ant 路径规则。
 * <p>
 * 生命周期：Phase 1 注册路由并调用 {@code servlet.init()}，销毁时调用 {@code servlet.destroy()}。
 */
@Slf4j
public class SupportServletRegistry extends BaseWebComponent {

    private final List<Servlet> initializedServlets = new ArrayList<>();

    @Override
    public void initComponentPhase1() throws Exception {
        MappingRegistry mappingRegistry = getWebContext().getWebComponent(MappingRegistry.class);
        if (mappingRegistry == null) {
            log.warn("MappingRegistry not available, Servlet routes will not be registered");
            return;
        }
        PerfServletContext servletContext = getWebContext().getWebComponent(PerfServletContext.class);
        if (servletContext == null) {
            // 无 ServletContext 时退化为原路径：没有 init-param/ServletContext 语义，直接 init + 注册
            log.warn("PerfServletContext not available, Servlet beans will be initialized without a ServletContext");
            Map<String, Servlet> beans = new LinkedHashMap<>(getWebContext().getCtx().getBeansOfType(Servlet.class));
            for (Servlet servlet : beans.values()) {
                registerServlet(servlet, null, mappingRegistry);
            }
            return;
        }
        // 1) Spring bean servlet 先进入统一注册表：静态注册先占 url-pattern，动态注册才知道冲突
        Map<String, Servlet> beans = new LinkedHashMap<>(getWebContext().getCtx().getBeansOfType(Servlet.class));
        for (Servlet servlet : beans.values()) {
            registerBeanServlet(servlet, servletContext);
        }
        // 2) 静态 + 动态（contextInitialized 里 addServlet 的）统一 init 并注册路由
        for (DynamicServletRegistration registration : servletContext.claimServletRegistrations()) {
            initAndMap(registration, servletContext, mappingRegistry);
        }
    }

    @Override
    public void destroyComponent() throws Exception {
        for (Servlet servlet : initializedServlets) {
            servlet.destroy();
        }
        initializedServlets.clear();
    }

    /**
     * 把 Spring bean servlet 登记到 ServletContext 的注册表（解析 {@link WebServlet} 后由
     * {@code PerfServletContext#registerBeanServlet} 统一存放）。解析不出 url-pattern 的 servlet 仍然跳过，避免 {@code /**} 遮蔽控制器。
     */
    private void registerBeanServlet(Servlet servlet, PerfServletContext servletContext) {
        WebServlet webServlet = AnnotatedElementUtils.findMergedAnnotation(servlet.getClass(), WebServlet.class);
        String servletName = resolveServletName(webServlet, servlet);
        String[] urlPatterns = resolveUrlPatterns(webServlet);
        if (urlPatterns == null) {
            // 无 @WebServlet 且无显式 URL pattern：无法确定路由，跳过注册避免 /** 全路径通配遮蔽控制器
            log.warn(
                    "Servlet {} has no @WebServlet url-pattern, skipping route registration. "
                            + "Annotate it with @WebServlet(urlPatterns=...) to expose it as a route.",
                    servlet.getClass().getName());
            return;
        }
        servletContext.registerBeanServlet(servletName, servlet, urlPatterns, resolveInitParams(webServlet));
    }

    /**
     * 认领一条注册项：{@code servlet.init()} 一次，每个 url-pattern 注册一条路由。
     * <p>
     * 没有 url-pattern 的 servlet（动态注册了但忘了 addMapping）不会收到请求，按规范不 init，并告警——避免"看起来注册成功"。
     * </p>
     */
    private void initAndMap(DynamicServletRegistration registration, PerfServletContext servletContext,
            MappingRegistry mappingRegistry) throws ServletException {
        if (registration.getMappings().isEmpty()) {
            log.warn("Servlet '{}' has no url-pattern mapping, it will not receive any request",
                    registration.getName());
            return;
        }
        Servlet servlet = registration.getServlet();
        servlet.init(new PerfServletConfig(registration.getName(), servletContext, registration.getInitParameters()));
        initializedServlets.add(servlet);
        ServletInvoker invoker = new ServletInvoker(servlet);
        for (String mapping : registration.getMappings()) {
            String pathRule = toPathRule(mapping);
            mappingRegistry.registerMapping(new PathMappingContext(invoker, pathRule));
            log.info("Mapped Servlet {} -> {}", registration.getName(), pathRule);
        }
    }

    private void registerServlet(Servlet servlet, PerfServletContext servletContext, MappingRegistry mappingRegistry)
            throws ServletException {
        WebServlet webServlet = AnnotatedElementUtils.findMergedAnnotation(servlet.getClass(), WebServlet.class);
        String servletName = resolveServletName(webServlet, servlet);
        String[] urlPatterns = resolveUrlPatterns(webServlet);
        if (urlPatterns == null) {
            // 无 @WebServlet 且无显式 URL pattern：无法确定路由，跳过注册避免 /** 全路径通配遮蔽控制器
            log.warn(
                    "Servlet {} has no @WebServlet url-pattern, skipping route registration. "
                            + "Annotate it with @WebServlet(urlPatterns=...) to expose it as a route.",
                    servlet.getClass().getName());
            return;
        }
        servlet.init(new PerfServletConfig(servletName, servletContext, resolveInitParams(webServlet)));
        initializedServlets.add(servlet);
        ServletInvoker invoker = new ServletInvoker(servlet);
        for (String pattern : urlPatterns) {
            String pathRule = toPathRule(pattern);
            PathMappingContext mappingContext = new PathMappingContext(invoker, pathRule);
            mappingRegistry.registerMapping(mappingContext);
            log.info("Mapped Servlet {} -> {}", servletName, pathRule);
        }
    }

    private String resolveServletName(WebServlet webServlet, Servlet servlet) {
        if (webServlet != null && !webServlet.name().isEmpty()) {
            return webServlet.name();
        }
        return servlet.getClass().getName();
    }

    /**
     * 解析 servlet 的 url-pattern。无 {@link WebServlet} 注解时返回 {@code null}（跳过注册）。
     */
    static String[] resolveUrlPatterns(WebServlet webServlet) {
        if (webServlet != null) {
            String[] patterns = webServlet.urlPatterns();
            if (patterns.length == 0) {
                patterns = webServlet.value();
            }
            if (patterns.length > 0) {
                return patterns;
            }
        }
        return null;
    }

    private Map<String, String> resolveInitParams(WebServlet webServlet) {
        if (webServlet == null) {
            return Collections.emptyMap();
        }
        WebInitParam[] initParams = webServlet.initParams();
        if (initParams == null || initParams.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>(initParams.length);
        for (WebInitParam param : initParams) {
            result.put(param.name(), param.value());
        }
        return result;
    }

    /**
     * 将 servlet url-pattern 转换为框架的 ant 路径规则：
     * <ul>
     * <li>默认映射 {@code /} → 全路径通配</li>
     * <li>路径映射 {@code /foo/*} → {@code /foo/**}</li>
     * <li>后缀映射（如 {@code *.txt}）→ 全路径后缀匹配</li>
     * <li>精确匹配 {@code /foo} → {@code /foo}</li>
     * </ul>
     */
    static String toPathRule(String urlPattern) {
        if (urlPattern == null) {
            return "/**";
        }
        String p = urlPattern.trim();
        if (p.isEmpty() || "/".equals(p)) {
            return "/**";
        }
        if (p.startsWith("*.")) {
            return "/**/" + p;
        }
        if (p.endsWith("/*")) {
            return p.substring(0, p.length() - 1) + "**";
        }
        return p;
    }
}
