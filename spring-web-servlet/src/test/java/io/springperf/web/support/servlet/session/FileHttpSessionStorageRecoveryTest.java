package io.springperf.web.support.servlet.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会话磁盘恢复的容错路径：坏文件、过期/失效数据、写盘失败与属性防御分支。 */
class FileHttpSessionStorageRecoveryTest {

    @TempDir
    Path tempDir;

    private final List<FileHttpSessionStorage> opened = new ArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(FileHttpSessionStorage::shutdown);
    }

    private FileHttpSessionStorage open() {
        FileHttpSessionStorage storage = new FileHttpSessionStorage(tempDir);
        opened.add(storage);
        return storage;
    }

    private Path writeSessionFile(String id, Consumer<HttpSessionData> configure) throws IOException {
        HttpSessionData data = new HttpSessionData(id, System.currentTimeMillis());
        configure.accept(data);
        Path file = tempDir.resolve(id + ".session");
        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(file))) {
            oos.writeObject(data);
        }
        return file;
    }

    private static ConcurrentMap<?, ?> readSessions(FileHttpSessionStorage storage) throws Exception {
        Field field = FileHttpSessionStorage.class.getDeclaredField("sessions");
        field.setAccessible(true);
        return (ConcurrentMap<?, ?>) field.get(storage);
    }

    @Test
    void constructor_nullExcludeListFallsBackToEmptySet() throws Exception {
        FileHttpSessionStorage storage = new FileHttpSessionStorage(tempDir, null);
        opened.add(storage);

        HttpSessionData session = storage.createSession();
        session.setAttribute("user", "alice");
        storage.saveSession(session);

        Path file = tempDir.resolve(session.getId() + ".session");
        assertTrue(Files.exists(file), "null 排除名单应回退为空集合，属性仍可正常落盘");
        try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(Files.newInputStream(file))) {
            HttpSessionData restored = (HttpSessionData) ois.readObject();
            assertEquals("alice", restored.getAttribute("user"), "落盘内容必须包含会话属性");
            assertEquals(session.getMaxInactiveInterval(), restored.getMaxInactiveInterval());
        }
    }

    @Test
    void constructor_uncreatableStoreDirFailsFast() throws Exception {
        Path blocker = Files.write(tempDir.resolve("blocker"), new byte[] { 1 });

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new FileHttpSessionStorage(blocker.resolve("nested")));

        assertTrue(ex.getMessage().contains("Failed to create session store-dir"));
        assertInstanceOf(IOException.class, ex.getCause());
    }

    @Test
    void loadExistingSessions_skipsExpiredAndInvalidFiles() throws Exception {
        Path expired = writeSessionFile("expired", data -> {
            data.setMaxInactiveInterval(1);
            data.setLastAccessedTime(System.currentTimeMillis() - 10_000);
        });
        Path invalid = writeSessionFile("invalid", data -> data.setInvalid(true));
        Path alive = writeSessionFile("alive", data -> data.setAttribute("user", "bob"));

        FileHttpSessionStorage storage = open();

        ConcurrentMap<?, ?> sessions = readSessions(storage);
        assertTrue(sessions.containsKey("alive"), "未过期会话应被恢复");
        assertFalse(sessions.containsKey("expired"));
        assertFalse(sessions.containsKey("invalid"));
        assertFalse(Files.exists(expired), "过期会话文件应被删除");
        assertFalse(Files.exists(invalid), "失效会话文件应被删除");
        assertTrue(Files.exists(alive));
    }

    @Test
    void loadExistingSessions_discardsWrongTypeFile() throws Exception {
        Path wrong = tempDir.resolve("wrong.session");
        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(wrong))) {
            oos.writeObject("not a session");
        }

        FileHttpSessionStorage storage = open();

        assertTrue(readSessions(storage).isEmpty());
        assertFalse(Files.exists(wrong), "非 HttpSessionData 内容应被删除");
    }

    @Test
    void loadExistingSessions_discardsCorruptedFile() throws Exception {
        Path corrupted = Files.write(tempDir.resolve("corrupt.session"), new byte[] { 1, 2, 3 });

        FileHttpSessionStorage storage = open();

        assertTrue(readSessions(storage).isEmpty());
        assertFalse(Files.exists(corrupted), "损坏文件应被删除，不影响启动");
    }

    @Test
    void saveSession_earlyReturnsForNullOrInvalidOrUnregistered() {
        FileHttpSessionStorage storage = open();

        storage.saveSession(null);

        HttpSessionData invalid = storage.createSession();
        invalid.setInvalid(true);
        storage.saveSession(invalid);

        HttpSessionData unregistered = new HttpSessionData("unregistered", System.currentTimeMillis());
        storage.saveSession(unregistered);

        assertFalse(Files.exists(tempDir.resolve(invalid.getId() + ".session")));
        assertFalse(Files.exists(tempDir.resolve("unregistered.session")));
    }

    @Test
    void saveSession_detectsInvalidationInsideLock() throws Exception {
        FileHttpSessionStorage storage = open();
        HttpSessionData real = storage.createSession();
        HttpSessionData session = spy(real);
        doReturn(false).doReturn(true).when(session).isInvalid();

        storage.saveSession(session);

        assertFalse(Files.exists(tempDir.resolve(real.getId() + ".session")),
                "锁内重查发现失效后不得再落盘");
        assertSame(real, readSessions(storage).get(real.getId()), "不得用 spy 覆盖在册会话");
    }

    @Test
    void buildCopy_skipsNullKeysAndValues() throws Exception {
        FileHttpSessionStorage storage = open();
        HttpSessionData source = new HttpSessionData("weird", System.currentTimeMillis()) {
            @Override
            public Map<String, Object> getAttributes() {
                Map<String, Object> attributes = new HashMap<>();
                attributes.put(null, "null-key");
                attributes.put("nullValue", null);
                attributes.put("kept", "value");
                return attributes;
            }
        };

        Method buildCopy = FileHttpSessionStorage.class.getDeclaredMethod("buildCopy",
                HttpSessionData.class, boolean.class);
        buildCopy.setAccessible(true);
        HttpSessionData copy = (HttpSessionData) buildCopy.invoke(storage, source, false);

        assertEquals(1, copy.getAttributes().size(), "null 键/值都不得进入序列化副本");
        assertEquals("value", copy.getAttributes().get("kept"));
    }

    @Test
    void saveSession_ioFailureCleansUpTmp() throws Exception {
        FileHttpSessionStorage storage = open();
        HttpSessionData session = storage.createSession();
        Path tmp = Files.createDirectory(tempDir.resolve(session.getId() + ".tmp"));

        storage.saveSession(session);

        assertFalse(Files.exists(tmp), "写 tmp 失败后必须清理，不能遗留半写文件");
        assertFalse(Files.exists(tempDir.resolve(session.getId() + ".session")));
    }

    @Test
    void deleteFileQuietly_swallowsNonEmptyDirectoryFailure() throws Exception {
        FileHttpSessionStorage storage = open();
        Path busy = Files.createDirectory(tempDir.resolve("busy"));
        Files.write(busy.resolve("child"), new byte[] { 1 });

        Method delete = FileHttpSessionStorage.class.getDeclaredMethod("deleteFileQuietly", Path.class);
        delete.setAccessible(true);
        delete.invoke(storage, busy);

        assertTrue(Files.isDirectory(busy), "删除失败应被吞掉，不得向外抛异常");
    }
}
