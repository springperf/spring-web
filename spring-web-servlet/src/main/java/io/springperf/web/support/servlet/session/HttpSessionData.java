package io.springperf.web.support.servlet.session;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话数据载体。
 *
 * <p>支持 JDK 序列化以配合 {@code server.servlet.session.persistent}（{@link FileHttpSessionStorage}）。
 * 注意：{@code attributes} 中的值必须可序列化，否则持久化时被跳过（见 {@link FileHttpSessionStorage}）。</p>
 */
public class HttpSessionData implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final long creationTime;
    private volatile long lastAccessedTime;
    private volatile int maxInactiveInterval;
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 会话失效状态，下沉到共享的 {@link HttpSessionData}（而非 PerfHttpSession wrapper 实例），
     * 使同一底层会话的多个并发 wrapper 能看到一致的失效状态，避免已失效会话被另一请求复活/
     * 重新持久化（L8）。
     */
    private volatile boolean invalid = false;

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

    public boolean isInvalid() {
        return invalid;
    }

    public void setInvalid(boolean invalid) {
        this.invalid = invalid;
    }
}
