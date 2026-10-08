package io.springperf.web.support.servlet.session;

import org.springframework.lang.Nullable;

import java.util.Map;

public interface HttpSessionStorage {

    @Nullable
    HttpSessionData getSession(String sessionId);

    HttpSessionData createSession();

    void saveSession(HttpSessionData session);

    void removeSession(String sessionId);

    /**
     * 会话 ID 轮换（{@code changeSessionId}）的存储侧原语：摘除旧会话、并把其属性复制到一个新 id 的会话。
     * <p>
     * 契约：**复制期间旧会话必须已不在册**——否则并发的 {@link #saveSession} 会把新写入落回旧会话，
     * 而属性表（{@code ConcurrentHashMap}）的弱一致迭代器可能漏读这些写入，导致登录态/token 静默丢失。 实现应保证「摘除旧会话 + 复制属性」是原子的。
     * </p>
     * <p>
     * 默认实现按「先摘除、再复制」的顺序做，对内存实现在同一线程内即已原子；带并发落盘的实现 （如文件存储）应覆写为在同一临界区内完成。
     * </p>
     *
     * @param oldId
     *            旧会话 id
     * @param oldData
     *            旧会话数据
     *
     * @return 已完成属性复制的新会话数据（已在册）
     */
    default HttpSessionData replaceSessionCopyingAttributes(String oldId, HttpSessionData oldData) {
        removeSession(oldId);
        HttpSessionData newData = createSession();
        for (Map.Entry<String, Object> entry : oldData.getAttributes().entrySet()) {
            newData.setAttribute(entry.getKey(), entry.getValue());
        }
        newData.setMaxInactiveInterval(oldData.getMaxInactiveInterval());
        saveSession(newData);
        return newData;
    }

    /**
     * 关闭存储，释放资源（如后台清理线程）。 默认无操作，子类按需覆写。
     */
    default void shutdown() {
    }
}
