package io.springperf.web.support.servlet.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
