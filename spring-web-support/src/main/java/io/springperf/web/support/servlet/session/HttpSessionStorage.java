package io.springperf.web.support.servlet.session;

import org.springframework.lang.Nullable;

public interface HttpSessionStorage {

    @Nullable
    HttpSessionData getSession(String sessionId);

    HttpSessionData createSession();

    void saveSession(HttpSessionData session);

    void removeSession(String sessionId);

    /**
     * 关闭存储，释放资源（如后台清理线程）。
     * 默认无操作，子类按需覆写。
     */
    default void shutdown() {
    }
}