package io.springperf.web.support.servlet.session;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HttpSessionData {

    private final String id;
    private final long creationTime;
    private volatile long lastAccessedTime;
    private volatile int maxInactiveInterval;
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();

    public HttpSessionData(String id, long creationTime) {
        this.id = id;
        this.creationTime = creationTime;
        this.lastAccessedTime = creationTime;
    }

    public String getId() { return id; }

    public long getCreationTime() { return creationTime; }

    public long getLastAccessedTime() { return lastAccessedTime; }

    public void setLastAccessedTime(long lastAccessedTime) { this.lastAccessedTime = lastAccessedTime; }

    public int getMaxInactiveInterval() { return maxInactiveInterval; }

    public void setMaxInactiveInterval(int maxInactiveInterval) { this.maxInactiveInterval = maxInactiveInterval; }

    public Map<String, Object> getAttributes() { return Collections.unmodifiableMap(attributes); }

    public Object getAttribute(String name) { return attributes.get(name); }

    public void setAttribute(String name, Object value) { attributes.put(name, value); }

    public void removeAttribute(String name) { attributes.remove(name); }

    public void clearAttributes() { attributes.clear(); }

    public boolean isExpired(long now) {
        return maxInactiveInterval > 0 && now - lastAccessedTime > maxInactiveInterval * 1000L;
    }
}