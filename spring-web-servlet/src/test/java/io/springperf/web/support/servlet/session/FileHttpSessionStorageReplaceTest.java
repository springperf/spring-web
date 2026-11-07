package io.springperf.web.support.servlet.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会话 id 轮换（整块未覆盖）与 store-dir 访问器的行为断言。 */
class FileHttpSessionStorageReplaceTest {

    @TempDir
    Path tempDir;

    private FileHttpSessionStorage storage;

    @BeforeEach
    void setUp() {
        storage = new FileHttpSessionStorage(tempDir);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
    }

    @Test
    void replaceSessionCopyingAttributes_migratesAttributesAndInterval() {
        HttpSessionData old = storage.createSession();
        old.setAttribute("user", "alice");
        old.setAttribute("count", 42);
        old.setMaxInactiveInterval(123);

        HttpSessionData copy = storage.replaceSessionCopyingAttributes(old.getId(), old);

        assertNotEquals(old.getId(), copy.getId(), "轮换必须生成新 id");
        assertEquals("alice", copy.getAttributes().get("user"));
        assertEquals(42, copy.getAttributes().get("count"));
        assertEquals(123L, copy.getMaxInactiveInterval());
        assertNull(storage.getSession(old.getId()), "旧会话应在锁内被摘除");
        assertNotNull(storage.getSession(copy.getId()), "新会话应登记在册");
    }

    @Test
    void replaceSessionCopyingAttributes_withEmptyAttributesKeepsDefaults() {
        HttpSessionData old = storage.createSession();

        HttpSessionData copy = storage.replaceSessionCopyingAttributes(old.getId(), old);

        assertNotEquals(old.getId(), copy.getId());
        assertTrue(copy.getAttributes().isEmpty());
        assertEquals(old.getMaxInactiveInterval(), copy.getMaxInactiveInterval());
    }

    @Test
    void getStoreDir_returnsConfiguredDirectory() {
        assertEquals(tempDir, storage.getStoreDir());
    }

    @Test
    void fileOf_rejectsIdThatEscapesStoreDir() throws Exception {
        Method fileOf = FileHttpSessionStorage.class.getDeclaredMethod("fileOf", String.class);
        fileOf.setAccessible(true);

        for (String unsafe : new String[] { "../escape", "a/b", "..\\windows" }) {
            Exception ex = assertThrows(Exception.class, () -> {
                try {
                    fileOf.invoke(storage, unsafe);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw (Exception) e.getCause();
                }
            }, "不安全 id 应被拒绝: " + unsafe);
            assertTrue(ex instanceof IllegalArgumentException, "实际异常: " + ex);
        }
    }

    @Test
    void fileOf_rejectsNullAndEmptyIds() {
        for (String invalid : new String[] { null, "" }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> callFileOf(invalid), "空 id 必须在任何文件操作前被拒绝: " + invalid);
            assertTrue(ex.getMessage().contains("must not be empty"));
        }
    }

    @Test
    void saveSession_unserializableAttributeSkipsPersistence() {
        HttpSessionData healthy = storage.createSession();
        storage.saveSession(healthy);
        assertTrue(Files.exists(tempDir.resolve(healthy.getId() + ".session")),
                "前置条件：正常会话可落盘，确保后续断言不是假通过");

        HttpSessionData session = storage.createSession();
        session.setAttribute("broken", new BrokenSerializable());
        Path target = tempDir.resolve(session.getId() + ".session");

        storage.saveSession(session);

        assertFalse(Files.exists(target), "序列化失败时不得写出半截会话文件");
        assertFalse(Files.exists(tempDir.resolve(session.getId() + ".tmp")), "失败路径不得遗留 tmp");
    }

    @Test
    void sweepExpired_removesOnlyExpiredSessions() throws Exception {
        HttpSessionData active = storage.createSession();
        HttpSessionData expired = storage.createSession();
        expired.setMaxInactiveInterval(1);
        expired.setLastAccessedTime(System.currentTimeMillis() - 10_000);
        Path expiredFile = tempDir.resolve(expired.getId() + ".session");
        storage.saveSession(expired);
        assertTrue(Files.exists(expiredFile), "前置条件：过期会话已落盘");

        assertEquals(1, storage.getActiveSessionCount(), "活跃会话数只应计入未过期会话");

        storage.sweepExpired(System.currentTimeMillis());

        ConcurrentMap<?, ?> sessions = readSessions();
        assertFalse(sessions.containsKey(expired.getId()), "过期会话应从内存条件摘除");
        assertTrue(sessions.containsKey(active.getId()), "活跃会话不得被误删");
        assertFalse(Files.exists(expiredFile), "过期会话文件应被删除");
    }

    @Test
    void parseDeserializationFilter_invalidSpecFailsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FileHttpSessionStorage.parseDeserializationFilter("maxdepth=not-a-number"));

        // 2.7.x 在 JDK 8 上通过反射访问 JDK 9+ 的 ObjectInputFilter：无该类型时 fail fast 并说明 JVM 限制。
        // master（JDK 17+）则会进入 ObjectInputFilter.Config.createFilter 的非法规格分支。
        assertTrue(ex.getMessage().contains("requires JDK 9+")
                || ex.getMessage().contains("Invalid session deserialization filter spec"),
                "实际消息: " + ex.getMessage());
        assertTrue(ex.getCause() instanceof IllegalArgumentException
                || ex.getCause() instanceof ClassNotFoundException,
                "实际 cause: " + ex.getCause());
    }

    private void callFileOf(String id) throws Exception {
        Method fileOf = FileHttpSessionStorage.class.getDeclaredMethod("fileOf", String.class);
        fileOf.setAccessible(true);
        try {
            fileOf.invoke(storage, new Object[] { id });
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private ConcurrentMap<?, ?> readSessions() throws Exception {
        Field field = FileHttpSessionStorage.class.getDeclaredField("sessions");
        field.setAccessible(true);
        return (ConcurrentMap<?, ?>) field.get(storage);
    }

    private static class BrokenSerializable implements Serializable {
        private static final long serialVersionUID = 1L;

        private void writeObject(ObjectOutputStream out) throws IOException {
            throw new IllegalStateException("broken on purpose");
        }
    }
}
