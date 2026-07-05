package io.springperf.web.support.servlet.session;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.*;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PerfHttpSession implements HttpSession {

    private final HttpSessionData data;
    private final ServletContext servletContext;
    private final List<HttpSessionListener> sessionListeners;
    private final List<HttpSessionAttributeListener> attributeListeners;
    private volatile boolean invalid;
    private volatile Runnable onInvalidateCallback;
    private volatile boolean newSession = true;

    public PerfHttpSession(HttpSessionData data, ServletContext servletContext) {
        this(data, servletContext, Collections.emptyList(), Collections.emptyList());
    }

    public PerfHttpSession(HttpSessionData data, ServletContext servletContext,
                           List<HttpSessionListener> sessionListeners,
                           List<HttpSessionAttributeListener> attributeListeners) {
        this.data = data;
        this.servletContext = servletContext;
        this.sessionListeners = sessionListeners;
        this.attributeListeners = attributeListeners;
    }

    @Override
    public long getCreationTime() {
        checkValid();
        return data.getCreationTime();
    }

    @Override
    public String getId() {
        checkValid();
        return data.getId();
    }

    @Override
    public long getLastAccessedTime() {
        checkValid();
        return data.getLastAccessedTime();
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        data.setMaxInactiveInterval(interval);
    }

    @Override
    public int getMaxInactiveInterval() {
        return data.getMaxInactiveInterval();
    }

    @Override
    public Object getAttribute(String name) {
        checkValid();
        return data.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        checkValid();
        return Collections.enumeration(data.getAttributes().keySet());
    }

    @Override
    public void setAttribute(String name, Object value) {
        checkValid();
        Object oldValue = data.getAttribute(name);
        data.setAttribute(name, value);
        if (!attributeListeners.isEmpty()) {
            if (oldValue != null) {
                HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, oldValue);
                for (HttpSessionAttributeListener listener : attributeListeners) {
                    listener.attributeReplaced(event);
                }
            } else {
                HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, value);
                for (HttpSessionAttributeListener listener : attributeListeners) {
                    listener.attributeAdded(event);
                }
            }
        }
    }

    @Override
    public void removeAttribute(String name) {
        checkValid();
        Object oldValue = data.getAttribute(name);
        data.removeAttribute(name);
        if (oldValue != null && !attributeListeners.isEmpty()) {
            HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, oldValue);
            for (HttpSessionAttributeListener listener : attributeListeners) {
                listener.attributeRemoved(event);
            }
        }
    }

    @Override
    public void invalidate() {
        checkValid();
        this.invalid = true;
        data.clearAttributes();
        Runnable callback = this.onInvalidateCallback;
        if (callback != null) {
            callback.run();
        }
        if (!sessionListeners.isEmpty()) {
            HttpSessionEvent event = new HttpSessionEvent(this);
            for (HttpSessionListener listener : sessionListeners) {
                listener.sessionDestroyed(event);
            }
        }
    }

    public boolean isInvalid() {
        return invalid;
    }

    @Override
    public boolean isNew() {
        checkValid();
        return newSession;
    }

    void setNotNew() {
        this.newSession = false;
    }

    public HttpSessionData getData() {
        return data;
    }

    public void markAccessed() {
        this.data.setLastAccessedTime(System.currentTimeMillis());
    }

    public void setOnInvalidateCallback(Runnable callback) {
        this.onInvalidateCallback = callback;
    }

    private void checkValid() {
        if (invalid) {
            throw new IllegalStateException("Session with id [" + data.getId() + "] has been invalidated");
        }
    }

    // ==================== Minimal ServletContext stub ====================

    public static ServletContext createMinimalServletContext() {
        return new MinimalServletContext();
    }

    private static class MinimalServletContext implements ServletContext {

        private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();

        @Override public String getContextPath() { return ""; }
        @Override public String getServletContextName() { return ""; }
        @Override public String getServerInfo() { return "spring-perf-web"; }
        @Override public int getMajorVersion() { return 4; }
        @Override public int getMinorVersion() { return 0; }
        @Override public int getEffectiveMajorVersion() { return 4; }
        @Override public int getEffectiveMinorVersion() { return 0; }

        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public Enumeration<String> getAttributeNames() { return Collections.enumeration(attributes.keySet()); }
        @Override public void setAttribute(String name, Object object) { attributes.put(name, object); }
        @Override public void removeAttribute(String name) { attributes.remove(name); }

        @Override public ServletContext getContext(String uripath) { return null; }
        @Override public String getMimeType(String file) { return null; }
        @Override public String getRealPath(String path) { return null; }
        @Override public java.net.URL getResource(String path) { return null; }
        @Override public java.io.InputStream getResourceAsStream(String path) { return null; }
        @Override public java.util.Set<String> getResourcePaths(String path) { return null; }
        @Override public jakarta.servlet.RequestDispatcher getRequestDispatcher(String path) { return null; }
        @Override public jakarta.servlet.RequestDispatcher getNamedDispatcher(String name) { return null; }
        @Override public String getInitParameter(String name) { return null; }
        @Override public Enumeration<String> getInitParameterNames() { return Collections.emptyEnumeration(); }
        @Override public boolean setInitParameter(String name, String value) { return false; }
        @Override public void log(String msg) { }
        @Override public void log(String message, Throwable throwable) { }
        @Override public jakarta.servlet.ServletRegistration.Dynamic addServlet(String servletName, String className) { return null; }
        @Override public jakarta.servlet.ServletRegistration.Dynamic addServlet(String servletName, jakarta.servlet.Servlet servlet) { return null; }
        @Override public jakarta.servlet.ServletRegistration.Dynamic addServlet(String servletName, Class<? extends jakarta.servlet.Servlet> servletClass) { return null; }
        @Override public jakarta.servlet.ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) { return null; }
        @Override public <T extends jakarta.servlet.Servlet> T createServlet(Class<T> c) { return null; }
        @Override public jakarta.servlet.ServletRegistration getServletRegistration(String servletName) { return null; }
        @Override public java.util.Map<String, ? extends jakarta.servlet.ServletRegistration> getServletRegistrations() { return Collections.emptyMap(); }
        @Override public jakarta.servlet.FilterRegistration.Dynamic addFilter(String filterName, String className) { return null; }
        @Override public jakarta.servlet.FilterRegistration.Dynamic addFilter(String filterName, jakarta.servlet.Filter filter) { return null; }
        @Override public jakarta.servlet.FilterRegistration.Dynamic addFilter(String filterName, Class<? extends jakarta.servlet.Filter> filterClass) { return null; }
        @Override public <T extends jakarta.servlet.Filter> T createFilter(Class<T> c) { return null; }
        @Override public jakarta.servlet.FilterRegistration getFilterRegistration(String filterName) { return null; }
        @Override public java.util.Map<String, ? extends jakarta.servlet.FilterRegistration> getFilterRegistrations() { return Collections.emptyMap(); }
        @Override public void addListener(String className) { }
        @Override public <T extends java.util.EventListener> T createListener(Class<T> c) { return null; }
        @Override public void addListener(Class<? extends java.util.EventListener> listenerClass) { }
        @Override public <T extends java.util.EventListener> void addListener(T t) { }
        @Override public jakarta.servlet.SessionCookieConfig getSessionCookieConfig() { return null; }
        @Override public void setSessionTrackingModes(java.util.Set<jakarta.servlet.SessionTrackingMode> sessionTrackingModes) { }
        @Override public java.util.Set<jakarta.servlet.SessionTrackingMode> getDefaultSessionTrackingModes() { return null; }
        @Override public java.util.Set<jakarta.servlet.SessionTrackingMode> getEffectiveSessionTrackingModes() { return null; }
        @Override public String getVirtualServerName() { return null; }
        @Override public int getSessionTimeout() { return 0; }
        @Override public void setSessionTimeout(int sessionTimeout) { }
        @Override public String getRequestCharacterEncoding() { return null; }
        @Override public void setRequestCharacterEncoding(String encoding) { }
        @Override public String getResponseCharacterEncoding() { return null; }
        @Override public void setResponseCharacterEncoding(String encoding) { }
        @Override public jakarta.servlet.descriptor.JspConfigDescriptor getJspConfigDescriptor() { return null; }
        @Override public ClassLoader getClassLoader() { return Thread.currentThread().getContextClassLoader(); }
        @Override public void declareRoles(String... roleNames) { }
    }
}