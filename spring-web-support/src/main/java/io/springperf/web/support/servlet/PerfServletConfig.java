package io.springperf.web.support.servlet;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

/**
 * 鍩轰簬妗嗘灦 {@link ServletContext} 鐨?{@link ServletConfig} 瀹炵幇锛?
 * 渚?{@code SupportServletRegistry} 鍦ㄥ惎鍔ㄦ椂璋冪敤 {@code servlet.init(ServletConfig)}銆?
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
