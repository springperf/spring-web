package io.springperf.web.support.servlet.filter;

import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

public class PerfFilterConfig implements FilterConfig {

    private final String filterName;
    private final ServletContext servletContext;
    private final Map<String, String> initParameters;

    public PerfFilterConfig(String filterName, ServletContext servletContext) {
        this(filterName, servletContext, Collections.emptyMap());
    }

    public PerfFilterConfig(String filterName, ServletContext servletContext, Map<String, String> initParameters) {
        this.filterName = filterName;
        this.servletContext = servletContext;
        this.initParameters = initParameters != null ? initParameters : Collections.emptyMap();
    }

    @Override
    public String getFilterName() {
        return filterName;
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