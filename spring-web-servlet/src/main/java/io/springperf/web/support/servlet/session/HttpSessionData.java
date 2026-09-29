package io.springperf.web.support.servlet.session;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话数据载体。
 * <p>
 * 支持 JDK 序列化以配合 {@code server.servlet.session.persistent}（{@link FileHttpSessionStorage}）。 注意：{@code attributes}
 * 中的值必须可序列化，否则持久化时被跳过（见 {@link FileHttpSessionStorage}）。
 * </p>
 */
public class HttpSessionData implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final long creationTime;
    private volatile long lastAccessedTime;
    private volatile int maxInactiveInterval;
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 会话失效状态，下沉到共享的 {@link HttpSessionData}（而非 PerfHttpSession wrapper 实例）， 使同一底层会话的多个并发 wrapper
     * 能看到一致的失效状态，避免已失效会话被另一请求复活/ 重新持久化（L8）。
     */
    private volatile boolean invalid = false;

    public HttpSessionData(String id, long creationTime) {
        this.id = id;
        this.creationTime = creationTime;
        this.lastAccessedTime = creationTime;
    }

    public String getId() {
        return id;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public long getLastAccessedTime() {
        return lastAccessedTime;
    }

    public void setLastAccessedTime(long lastAccessedTime) {
        this.lastAccessedTime = lastAccessedTime;
    }

    public int getMaxInactiveInterval() {
        return maxInactiveInterval;
    }

    public void setMaxInactiveInterval(int maxInactiveInterval) {
        this.maxInactiveInterval = maxInactiveInterval;
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    public void clearAttributes() {
        attributes.clear();
    }

    public boolean isExpired(long now) {
        return maxInactiveInterval > 0 && now - lastAccessedTime > maxInactiveInterval * 1000L;
    }

    public boolean isInvalid() {
        return invalid;
    }

    public void setInvalid(boolean invalid) {
        this.invalid = invalid;
    }

    /**
     * 原子地抢占"失效"状态：仅当当前仍有效时置为失效并返回 true。
     * <p>
     * 用于替代 {@link PerfHttpSession#invalidate()} 中"先 isInvalid() 判断再 setInvalid(true)"的 check-then-act： 该写法在
     * 同一底层会话被并发失效时（多个 wrapper 实例共享本对象，或两个请求同时调用 invalidate） 可让两个线程同时通过校验，导致失效回调与 sessionDestroyed
     * 被重复触发。本方法把判断与置位合成一个临界区，保证有且只有一个调用者获得 true。
     * </p>
     * <p>
     * 仅 invalidate 路径使用，非每请求热路径，synchronized 开销可接受；读侧 {@link #isInvalid()} 仍保持无锁。
     * </p>
     *
     * @return true 表示本次调用成功抢占，调用方负责执行销毁侧效应；false 表示会话已被其它线程失效
     */
    public synchronized boolean tryInvalidate() {
        if (invalid) {
            return false;
        }
        invalid = true;
        return true;
    }
}
