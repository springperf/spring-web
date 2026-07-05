package io.springperf.web.support.servlet.session;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryHttpSessionStorage implements HttpSessionStorage {

    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    private final ConcurrentMap<String, HttpSessionData> sessions = new ConcurrentHashMap<>();
    private final Thread cleanupThread;

    public InMemoryHttpSessionStorage() {
        this.cleanupThread = new Thread(this::cleanupLoop, "session-cleanup");
        this.cleanupThread.setDaemon(true);
        this.cleanupThread.start();
    }

    @Override
    public HttpSessionData getSession(String sessionId) {
        HttpSessionData session = sessions.get(sessionId);
        if (session != null && session.isExpired(System.currentTimeMillis())) {
            sessions.remove(sessionId, session);
            return null;
        }
        return session;
    }

    @Override
    public HttpSessionData createSession() {
        String id = generateSessionId();
        HttpSessionData session = new HttpSessionData(id, System.currentTimeMillis());
        sessions.put(id, session);
        return session;
    }

    @Override
    public void saveSession(HttpSessionData session) {
        // In-memory: already stored by reference, no-op
    }

    @Override
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public int getActiveSessionCount() {
        long now = System.currentTimeMillis();
        return (int) sessions.values().stream()
                .filter(s -> !s.isExpired(now))
                .count();
    }

    private static final java.security.SecureRandom SESSION_ID_RANDOM = new java.security.SecureRandom();

    private static String generateSessionId() {
        byte[] bytes = new byte[32];
        SESSION_ID_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    void clear() {
        sessions.clear();
    }

    /**
     * Shutdown the cleanup thread. Should be called when the storage is no longer needed.
     */
    public void shutdown() {
        cleanupThread.interrupt();
    }

    private void cleanupLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(CLEANUP_INTERVAL_MS);
                long now = System.currentTimeMillis();
                sessions.values().removeIf(s -> s.isExpired(now));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}