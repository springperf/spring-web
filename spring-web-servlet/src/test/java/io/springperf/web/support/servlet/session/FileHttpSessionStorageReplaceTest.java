package io.springperf.web.support.servlet.session;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Path;

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
}
