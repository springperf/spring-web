package io.springperf.web.support.servlet.session;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
     * 内容版本号：每次「会改变序列化结果」的写操作（setAttribute/removeAttribute/clearAttributes/ setMaxInactiveInterval）递增。持久化落盘时快照
     * version，写盘成功后把快照回填到 persistedVersion。
     * <p>
     * 用「版本快照 + 回填」而非简单 boolean 脏标记的原因：保存发生在后台单线程，业务线程可能并发写属性。 保存开始时记录 version，序列化的是那一刻的内容；若写盘期间又有属性变更（version 前进）， 则
     * version != persistedVersion，下次请求会再次触发落盘——不会丢失并发写入。
     * </p>
     * <p>
     * transient：重启恢复后一律视为「需重新落盘一次」（persistedVersion 回到初始值 -1），语义安全。
     * </p>
     */
    private transient AtomicLong contentVersion = new AtomicLong();
    private transient volatile long persistedVersion = -1;
    /** 上次真正落盘的 lastAccessedTime，用于「仅访问时间变化」场景的节流。 */
    private transient volatile long persistedAccessTime = Long.MIN_VALUE;

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

    /**
     * 反序列化后重建 transient 运行时字段：脏检查状态不该随会话内容落盘。 恢复后一律视为「尚未落盘」（persistedVersion=-1），首个请求会重新落盘一次，语义安全。
     */
    private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
        in.defaultReadObject();
        this.contentVersion = new AtomicLong();
        this.persistedVersion = -1;
        this.persistedAccessTime = Long.MIN_VALUE;
    }

    public String getId() {
        return id;
    }

    public long getCreationTime() { return creationTime; }

    public long getLastAccessedTime() { return lastAccessedTime; }

    public void setLastAccessedTime(long lastAccessedTime) { this.lastAccessedTime = lastAccessedTime; }

    public int getMaxInactiveInterval() { return maxInactiveInterval; }

    public void setMaxInactiveInterval(int maxInactiveInterval) {
        this.maxInactiveInterval = maxInactiveInterval;
        contentVersion.incrementAndGet();
    }

    public Map<String, Object> getAttributes() { return Collections.unmodifiableMap(attributes); }

    public Object getAttribute(String name) { return attributes.get(name); }

    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
        contentVersion.incrementAndGet();
    }

    public void removeAttribute(String name) {
        attributes.remove(name);
        contentVersion.incrementAndGet();
    }

    public void clearAttributes() {
        attributes.clear();
        contentVersion.incrementAndGet();
    }

    // ==================== 持久化脏检查支持 ====================

    /** 当前内容版本号（保存开始时快照）。 */
    public long getContentVersion() {
        return contentVersion.get();
    }

    /** 已成功落盘的内容版本号；-1 表示尚未落盘过。 */
    public long getPersistedVersion() {
        return persistedVersion;
    }

    /** 上次真正落盘的访问时间；{@link Long#MIN_VALUE} 表示从未落盘。 */
    public long getPersistedAccessTime() {
        return persistedAccessTime;
    }

    /**
     * 落盘成功后回填版本与访问时间。
     * <p>
     * 参数 versionSnapshot 是保存开始时快照的 version。回填时若 version 已前进（写盘期间有并发属性写）， 只更新访问时间、不推进 persistedVersion —— 这样下次请求发现
     * version != persistedVersion，会再落盘一次， 覆盖写盘期间的新写入，避免丢失。
     * </p>
     */
    public void markPersisted(long versionSnapshot, long accessTime) {
        this.persistedAccessTime = accessTime;
        if (this.contentVersion.get() == versionSnapshot) {
            this.persistedVersion = versionSnapshot;
        }
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
     * 用于替代 {@link PerfHttpSession#invalidate()} 中"先 isInvalid() 判断再 setInvalid(true)"的 check-then-act：
     * 该写法在同一底层会话被并发失效时（多个 wrapper 实例共享本对象，或两个请求同时调用 invalidate）
     * 可让两个线程同时通过校验，导致失效回调与 sessionDestroyed 被重复触发。本方法把判断与置位合成一个临界区，
     * 保证有且只有一个调用者获得 true。
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
