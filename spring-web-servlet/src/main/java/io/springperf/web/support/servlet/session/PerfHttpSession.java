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

    // ---- 以下 5 个方法是 javax.servlet.http.HttpSession 的 @Deprecated 成员 ----
    // 本分支（Servlet 4.0 / javax）的接口仍声明它们，故必须实现；master（jakarta Servlet 6.0）
    // 已从接口移除，故那边没有这些方法。这不是「本分支漏删」，而是接口版本差异——
    // 归一化比对时它们恒为残差，勿按漏改处理。
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
        // 原子抢占失效状态后再执行销毁侧效应。若沿用「先 checkValid() 再 setInvalid(true)」，
        // 并发 invalidate 可同时通过校验，导致 onInvalidateCallback 与 sessionDestroyed 各触发两次。
        // 抢占失败即会话已失效，按 Servlet 规范语义抛 IllegalStateException（与 checkValid() 行为一致）。
        if (!data.tryInvalidate()) {
            throw new IllegalStateException("Session with id [" + data.getId() + "] has been invalidated");
        }
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
        return data.isInvalid();
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
        if (data.isInvalid()) {
            throw new IllegalStateException("Session with id [" + data.getId() + "] has been invalidated");
        }
    }

}
