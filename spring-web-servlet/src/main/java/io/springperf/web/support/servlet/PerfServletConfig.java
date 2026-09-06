package io.springperf.web.support.servlet;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

/**
 * 基于框架 {@link ServletContext} 的 {@link ServletConfig} 实现，
 * 供 {@code SupportServletRegistry} 在启动时调用 {@code servlet.init(ServletConfig)}。
 */
public class PerfServletConfig implements ServletConfig {

    private final String servletName;
    private final ServletContext servletContext;
    private final Map<String, String> initParameters;

    public PerfServletConfig(String servletName, ServletContext servletContext) {
        this(servletName, servletContext, Collections.emptyMap());
    }

    public PerfServletConfig(String servletName, ServletContext servletContext, Map<String, String> initParameters) {
        this.servletName = servletName;
        this.servletContext = servletContext;
        this.initParameters = initParameters != null ? initParameters : Collections.emptyMap();
    }

    @Override
    public String getServletName() {
        return servletName;
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public String getInitParameter(String name) {
        return initParameters.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }
}
