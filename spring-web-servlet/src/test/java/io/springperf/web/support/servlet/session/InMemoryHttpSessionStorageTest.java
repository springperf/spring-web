package io.springperf.web.support.servlet.session;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归 P2 性能组 #3：generateSessionId 从每字节 String.format 改为 hex 查找表。
 * <p>行为契约不变：32 字节 SecureRandom → 64 字符小写十六进制，字符集仅 [0-9a-f]。</p>
 */
class InMemoryHttpSessionStorageTest {

    @Test
    void createSession_idHasExpectedLength() {
        InMemoryHttpSessionStorage storage = new InMemoryHttpSessionStorage();
        try {
            String id = storage.createSession().getId();
            assertEquals(64, id.length(), "32 字节 → 64 字符 hex");
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void createSession_idIsLowercaseHexOnly() {
        InMemoryHttpSessionStorage storage = new InMemoryHttpSessionStorage();
        try {
            String id = storage.createSession().getId();
            assertTrue(id.matches("[0-9a-f]{64}"), "session id 必须全小写 hex: " + id);
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void createSession_idsAreDistinct() {
        InMemoryHttpSessionStorage storage = new InMemoryHttpSessionStorage();
        try {
            assertNotEquals(storage.createSession().getId(), storage.createSession().getId());
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void getSession_missing_returnsNull() {
        InMemoryHttpSessionStorage storage = new InMemoryHttpSessionStorage();
        try {
            assertNull(storage.getSession("nonexistent"));
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void getSession_existing_returnsDataWithAttributes() {
        InMemoryHttpSessionStorage storage = new InMemoryHttpSessionStorage();
        try {
            HttpSessionData created = storage.createSession();
            created.setAttribute("k", "v");
            HttpSessionData fetched = storage.getSession(created.getId());
            assertNotNull(fetched);
            assertEquals("v", fetched.getAttribute("k"));
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void getSession_expired_returnsNullAndRemoves() {
        InMemoryHttpSessionStorage storage = new InMemoryHttpSessionStorage();
        try {
            HttpSessionData created = storage.createSession();
            created.setLastAccessedTime(System.currentTimeMillis() - 100_000L);
            created.setMaxInactiveInterval(1);
            assertNull(storage.getSession(created.getId()), "过期 session 应返回 null 并清理");
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void removeSession_removesFromStore() {
        InMemoryHttpSessionStorage storage = new InMemoryHttpSessionStorage();
        try {
            HttpSessionData created = storage.createSession();
            storage.removeSession(created.getId());
            assertNull(storage.getSession(created.getId()));
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void saveSession_isNoOp() {
        InMemoryHttpSessionStorage storage = new InMemoryHttpSessionStorage();
        try {
            HttpSessionData data = storage.createSession();
            assertDoesNotThrow(() -> storage.saveSession(data));
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void getActiveSessionCount_countsNonExpired() {
        InMemoryHttpSessionStorage storage = new InMemoryHttpSessionStorage();
        try {
            storage.createSession();
            HttpSessionData expired = storage.createSession();
            expired.setLastAccessedTime(System.currentTimeMillis() - 100_000L);
            expired.setMaxInactiveInterval(1);
            assertEquals(1, storage.getActiveSessionCount(), "过期 session 不应计入活跃会话数");
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void shutdown_interruptsCleanupThread() {
        InMemoryHttpSessionStorage storage = new InMemoryHttpSessionStorage();
        assertDoesNotThrow(storage::shutdown);
        assertDoesNotThrow(storage::shutdown, "重复 shutdown 应安全");
    }

    @Test
    void createSession_dataExposesIdCreationAndAttributes() {
        HttpSessionData data = new HttpSessionData("id-1", 1234L);
        assertEquals("id-1", data.getId());
        assertEquals(1234L, data.getCreationTime());
        data.setAttribute("a", 1);
        data.setAttribute("b", "x");
        assertEquals(1, data.getAttribute("a"));
        assertEquals("x", data.getAttribute("b"));
        assertEquals(2, data.getAttributes().size());
        assertTrue(data.getAttributes() instanceof Map);
        data.removeAttribute("a");
        assertNull(data.getAttribute("a"));
        data.clearAttributes();
        assertTrue(data.getAttributes().isEmpty());
    }
}
