package io.springperf.web.support.servlet.context;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletSecurityElement;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@link ServletRegistration.Dynamic} 实现。
 * <p>
 * 只在 ServletContext 初始化窗口内（{@code ServletContextListener#contextInitialized} 期间）可登记； Phase1 结束后由
 * {@code SupportServletRegistry} 认领：调用 {@code servlet.init()}，并把每个 url-pattern 转成框架路由。
 * </p>
 * <p>
 * 本框架没有 Servlet 安全约束、servlet 级 multipart 与 load-on-startup 的调度模型，相关 setter 只记录配置并返回「未应用」： {@link #setServletSecurity}
 * 返回全部映射表示一条都没应用，{@link #setMultipartConfig} 打印一次告警。 这类能力缺口必须显形，不能假装接受。
 * </p>
 */
@Slf4j
public class DynamicServletRegistration implements ServletRegistration.Dynamic {

    private final String name;

    private final Servlet servlet;

    private final String className;

    private final PerfServletContext servletContext;

    private final Map<String, String> initParameters = new LinkedHashMap<>(4);

    private final Set<String> mappings = new LinkedHashSet<>(4);

    private volatile boolean asyncSupported;

    private volatile boolean claimed;

    private volatile int loadOnStartup = -1;

    private MultipartConfigElement multipartConfig;

    private String runAsRole;

    DynamicServletRegistration(String name, Servlet servlet, PerfServletContext servletContext) {
        this.name = name;
        this.servlet = servlet;
        this.className = servlet.getClass().getName();
        this.servletContext = servletContext;
    }

    @Override
    public Set<String> addMapping(String... urlPatterns) {
        if (urlPatterns == null || urlPatterns.length == 0) {
            throw new IllegalArgumentException("urlPatterns must not be null or empty");
        }
        Set<String> conflicts = new LinkedHashSet<>();
        for (String urlPattern : urlPatterns) {
            String pattern = validatePattern(urlPattern);
            if (servletContext.isUrlPatternTaken(pattern, name)) {
                conflicts.add(pattern);
                continue;
            }
            mappings.add(pattern);
        }
        return conflicts.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(conflicts);
    }

    /**
     * Servlet 规范的 url-pattern 形态：{@code /}（默认）、{@code /path}（精确）、{@code /path/*}（前缀）、 {@code *.ext}（扩展名）。非法形态直接失败，不做猜测。
     */
    static String validatePattern(String urlPattern) {
        if (urlPattern == null) {
            throw new IllegalArgumentException("url-pattern must not be null");
        }
        String pattern = urlPattern.trim();
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("url-pattern must not be empty");
        }
        if (pattern.equals("/") || pattern.startsWith("/") || pattern.startsWith("*.")) {
            return pattern;
        }
        throw new IllegalArgumentException(
                "Invalid url-pattern (must be '/', '/path', '/path/*' or '*.ext'): " + urlPattern);
    }

    @Override
    public Collection<String> getMappings() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(mappings));
    }

    @Override
    public String getRunAsRole() {
        return runAsRole;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getClassName() {
        return className;
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Init parameter name must not be null or empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("Init parameter value must not be null: " + name);
        }
        if (initParameters.containsKey(name)) {
            return false;
        }
        initParameters.put(name, value);
        return true;
    }

    @Override
    public String getInitParameter(String name) {
        return initParameters.get(name);
    }

    @Override
    public Set<String> setInitParameters(Map<String, String> initParameters) {
        if (initParameters == null) {
            throw new IllegalArgumentException("initParameters must not be null");
        }
        Set<String> conflicts = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : initParameters.entrySet()) {
            if (!setInitParameter(entry.getKey(), entry.getValue())) {
                conflicts.add(entry.getKey());
            }
        }
        return conflicts.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(conflicts);
    }

    @Override
    public Map<String, String> getInitParameters() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(initParameters));
    }

    @Override
    public void setLoadOnStartup(int loadOnStartup) {
        this.loadOnStartup = loadOnStartup;
    }

    @Override
    public Set<String> setServletSecurity(ServletSecurityElement constraint) {
        // 本框架没有请求级安全约束模型，返回全部映射 = 一条都没应用（规范允许返回未能应用的 URL pattern 集合）
        Set<String> notApplied = new LinkedHashSet<>(getMappings());
        log.warn("Servlet {} : setServletSecurity is not supported by this framework, "
                + "{} mapping(s) left without security constraints. Enforce authorization in a filter/interceptor.",
                name, notApplied.size());
        return Collections.unmodifiableSet(notApplied);
    }

    @Override
    public void setMultipartConfig(MultipartConfigElement multipartConfig) {
        this.multipartConfig = multipartConfig;
        // 框架的 multipart 解析是全局的，per-servlet 配置无法生效；静默接受会造成"以为限制了大小"的错觉
        log.warn(
                "Servlet {} : per-servlet multipart-config is not supported, the global multipart configuration applies",
                name);
    }

    @Override
    public void setRunAsRole(String roleName) {
        this.runAsRole = roleName;
    }

    @Override
    public void setAsyncSupported(boolean isAsyncSupported) {
        this.asyncSupported = isAsyncSupported;
    }

    int getLoadOnStartup() {
        return loadOnStartup;
    }

    MultipartConfigElement getMultipartConfig() {
        return multipartConfig;
    }

    boolean isAsyncSupported() {
        return asyncSupported;
    }

    /** 被登记的 servlet 实例（{@code SupportServletRegistry} 认领后用于 init 与路由绑定）。 */
    public Servlet getServlet() {
        return servlet;
    }

    /** 是否已被 {@code SupportServletRegistry} 认领（认领后不再重复 init/注册路由）。 */
    boolean isClaimed() {
        return claimed;
    }

    /** 原子认领：返回 true 表示本次调用方负责 init + 注册路由（并发重复认领会返回 false）。 */
    synchronized boolean markClaimedIfUnclaimed() {
        if (claimed) {
            return false;
        }
        claimed = true;
        return true;
    }
}
