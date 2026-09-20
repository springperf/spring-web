package io.springperf.web.support.servlet.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileHttpSessionStorage} 单元测试：持久化 / 重启恢复 / 过期清理 / 坏文件容错 /
 * 不可序列化与排除名单属性跳过。
 */
class FileHttpSessionStorageTest {

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

    private static final class SerializableValue implements Serializable {
        private static final long serialVersionUID = 1L;
        final String text;
        SerializableValue(String text) { this.text = text; }
    }

    /** 不可序列化的属性值（无 Serializable）。 */
    private static final class NotSerializable {
    }

    @Test
    void createAndSave_writesSessionFile() throws Exception {
        HttpSessionData data = storage.createSession();
        data.setAttribute("user", new SerializableValue("alice"));
        storage.saveSession(data);

        Path file = tempDir.resolve(data.getId() + ".session");
        assertTrue(Files.exists(file), "应写入 <id>.session 文件");
        assertTrue(Files.size(file) > 0);
    }

    @Test
    void saveSession_noTmpFileLeft()throws Exception {
        HttpSessionData data = storage.createSession();
        data.setAttribute("k", "v");
        storage.saveSession(data);

        try (var files = Files.list(tempDir)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "原子写完成后不应残留 .tmp 文件");
        }
    }

    @Test
    void restart_restoresNonExpiredSession() throws Exception {
        HttpSessionData data = storage.createSession();
        data.setMaxInactiveInterval(3600);
        data.setAttribute("user", new SerializableValue("bob"));
        storage.saveSession(data);
        String id = data.getId();
        storage.shutdown();

        // 模拟重启：新实例从同一目录加载
        FileHttpSessionStorage restarted = new FileHttpSessionStorage(tempDir);
        try {
            HttpSessionData restored = restarted.getSession(id);
            assertNotNull(restored, "重启后应恢复未过期会话");
            assertEquals("bob", ((SerializableValue) restored.getAttribute("user")).text);
            assertEquals(3600, restored.getMaxInactiveInterval());
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void restart_expiredSession_notRestored() throws Exception {
        HttpSessionData data = storage.createSession();
        // 已过期：maxInactiveInterval=1s，lastAccessed 置为很早
        data.setMaxInactiveInterval(1);
        data.setLastAccessedTime(System.currentTimeMillis() - 10_000L);
        storage.saveSession(data);
        String id = data.getId();
        storage.shutdown();

        FileHttpSessionStorage restarted = new FileHttpSessionStorage(tempDir);
        try {
            assertNull(restarted.getSession(id), "已过期会话不应恢复");
            assertFalse(Files.exists(tempDir.resolve(id + ".session")), "过期会话文件应被删除");
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void removeSession_deletesFile() throws Exception {
        HttpSessionData data = storage.createSession();
        data.setAttribute("k", "v");
        storage.saveSession(data);
        Path file = tempDir.resolve(data.getId() + ".session");
        assertTrue(Files.exists(file));

        storage.removeSession(data.getId());
        assertFalse(Files.exists(file), "removeSession 应删除文件");
        assertNull(storage.getSession(data.getId()));
    }

    @Test
    void notSerializableAttribute_skippedOthersPersisted() throws Exception {
        HttpSessionData data = storage.createSession();
        data.setMaxInactiveInterval(3600);
        data.setAttribute("good", new SerializableValue("keep"));
        data.setAttribute("bad", new NotSerializable());
        storage.saveSession(data);
        String id = data.getId();
        storage.shutdown();

        FileHttpSessionStorage restarted = new FileHttpSessionStorage(tempDir);
        try {
            HttpSessionData restored = restarted.getSession(id);
            assertNotNull(restored);
            assertNotNull(restored.getAttribute("good"), "可序列化属性应保留");
            assertNull(restored.getAttribute("bad"), "不可序列化属性应被跳过");
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void excludedAttribute_notPersisted() throws Exception {
        Path dir = tempDir.resolve("excluded");
        FileHttpSessionStorage excluded = new FileHttpSessionStorage(
                dir, Collections.singleton("secret"));
        try {
            HttpSessionData data = excluded.createSession();
            data.setMaxInactiveInterval(3600);
            data.setAttribute("secret", new SerializableValue("s"));
            data.setAttribute("keep", new SerializableValue("k"));
            excluded.saveSession(data);
            String id = data.getId();
            excluded.shutdown();

            FileHttpSessionStorage restarted = new FileHttpSessionStorage(
                    dir, Collections.singleton("secret"));
            try {
                HttpSessionData restored = restarted.getSession(id);
                assertNotNull(restored);
                assertNull(restored.getAttribute("secret"), "排除名单属性不应持久化");
                assertNotNull(restored.getAttribute("keep"));
            } finally {
                restarted.shutdown();
            }
        } finally {
            excluded.shutdown();
        }
    }

    @Test
    void corruptedFile_skippedWithoutFailingStartup() throws Exception {
        // 写入一个损坏的 .session 文件
        Path bad = tempDir.resolve("deadbeef.session");
        Files.write(bad, new byte[]{1, 2, 3, 4, 5});

        // 启动不应抛异常
        FileHttpSessionStorage restarted = new FileHttpSessionStorage(tempDir);
        try {
            assertEquals(0, restarted.getActiveSessionCount());
            assertFalse(Files.exists(bad), "损坏文件应被删除");
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void getSession_expired_returnsNullAndDeletes() throws Exception {
        HttpSessionData data = storage.createSession();
        data.setMaxInactiveInterval(1);
        data.setLastAccessedTime(System.currentTimeMillis() - 10_000L);

        assertNull(storage.getSession(data.getId()));
        assertFalse(Files.exists(tempDir.resolve(data.getId() + ".session")));
    }

    @Test
    void invalidSession_notPersisted() throws Exception {
        HttpSessionData data = storage.createSession();
        data.setAttribute("k", "v");
        data.setInvalid(true);
        storage.saveSession(data);

        assertFalse(Files.exists(tempDir.resolve(data.getId() + ".session")),
                "已失效会话不应写入磁盘");
    }

    @Test
    void parseExcludeList_handlesBlankAndWhitespace() {
        assertEquals(Set.of(), FileHttpSessionStorage.parseExcludeList(null));
        assertEquals(Set.of(), FileHttpSessionStorage.parseExcludeList("  "));
        assertEquals(Set.of("a", "b"), FileHttpSessionStorage.parseExcludeList(" a , b , "));
        assertEquals(List.of("x"), List.copyOf(FileHttpSessionStorage.parseExcludeList("x")));
    }

    @Test
    void generateSessionId_isHexAndUnique() {
        HttpSessionData a = storage.createSession();
        HttpSessionData b = storage.createSession();
        assertEquals(64, a.getId().length(), "32 字节 → 64 位十六进制");
        assertTrue(a.getId().matches("[0-9a-f]{64}"));
        assertFalse(a.getId().equals(b.getId()));
    }
}
