package io.springperf.web.support.servlet.session;

import javax.servlet.ServletContext;
import javax.servlet.http.*;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

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
    public javax.servlet.http.HttpSessionContext getSessionContext() {
        throw new UnsupportedOperationException("getSessionContext is deprecated and not supported");
    }

    @Override
    public Object getAttribute(String name) {
        checkValid();
        return data.getAttribute(name);
    }

    @Override
    public Object getValue(String name) {
        return getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        checkValid();
        return Collections.enumeration(data.getAttributes().keySet());
    }

    @Override
    public String[] getValueNames() {
        checkValid();
        return data.getAttributes().keySet().toArray(new String[0]);
    }

    @Override
    public void setAttribute(String name, Object value) {
        checkValid();
        Object oldValue = data.getAttribute(name);
        data.setAttribute(name, value);
        if (oldValue != null) {
            unbindValue(name, oldValue);
            if (!attributeListeners.isEmpty()) {
                HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, oldValue);
                for (HttpSessionAttributeListener listener : attributeListeners) {
                    listener.attributeReplaced(event);
                }
            }
        } else {
            if (!attributeListeners.isEmpty()) {
                HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, value);
                for (HttpSessionAttributeListener listener : attributeListeners) {
                    listener.attributeAdded(event);
                }
            }
        }
        bindValue(name, value);
    }

    @Override
    public void putValue(String name, Object value) {
        setAttribute(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        checkValid();
        Object oldValue = data.getAttribute(name);
        if (oldValue == null) {
            return;
        }
        data.removeAttribute(name);
        unbindValue(name, oldValue);
        if (!attributeListeners.isEmpty()) {
            HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, oldValue);
            for (HttpSessionAttributeListener listener : attributeListeners) {
                listener.attributeRemoved(event);
            }
        }
    }

    @Override
    public void removeValue(String name) {
        removeAttribute(name);
    }

    @Override
    public void invalidate() {
        checkValid();
        this.invalid = true;
        Map<String, Object> attrs = new java.util.HashMap<>(data.getAttributes());
        data.clearAttributes();
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            unbindValue(entry.getKey(), entry.getValue());
        }
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

    private void bindValue(String name, Object value) {
        if (value instanceof HttpSessionBindingListener) {
            ((HttpSessionBindingListener) value).valueBound(new HttpSessionBindingEvent(this, name));
        }
    }

    private void unbindValue(String name, Object value) {
        if (value instanceof HttpSessionBindingListener) {
            ((HttpSessionBindingListener) value).valueUnbound(new HttpSessionBindingEvent(this, name));
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

}
