package io.springperf.web.support.servlet.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.io.ObjectInputFilter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FileHttpSessionStorage} 单元测试：持久化 / 重启恢复 / 过期清理 / 坏文件容错 / 不可序列化与排除名单属性跳过。
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

    /**
     * 路径安全：id 含分隔符 / 上跳片段时必须在任何文件操作前失败。
     * <p>
     * 起因是一次审查：{@code fileOf} 直接把 id 拼进路径，安全性依赖「调用方只传自己生成的 id」这一跨方法约定。 现在校验落在 {@code fileOf}
     * 内，本用例把它固定下来——若有人为了「按请求懒加载」而放行外来 id，此处会先红。
     * </p>
     */
    @Test
    void fileOfRejectsSessionIdsThatCouldEscapeStoreDir() {
        for (String unsafe : new String[] { "../../etc/passwd", "..\\..\\windows\\system32\\x", "a/b", "..", "" }) {
            assertThrows(IllegalArgumentException.class, () -> storage.removeSession(unsafe), "应拒绝不安全 id: " + unsafe);
        }
        // 正常 id（含连字符等普通字符）不受影响
        HttpSessionData data = storage.createSession();
        assertNotNull(storage.getSession(data.getId()));
        storage.removeSession(data.getId());
        assertNull(storage.getSession(data.getId()));
    }

    private static final class SerializableValue implements Serializable {
        private static final long serialVersionUID = 1L;
        final String text;

        SerializableValue(String text) {
            this.text = text;
        }
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
    void saveSession_noTmpFileLeft() throws Exception {
        HttpSessionData data = storage.createSession();
        data.setAttribute("k", "v");
        storage.saveSession(data);

        try (var files = Files.list(tempDir)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")), "原子写完成后不应残留 .tmp 文件");
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
        FileHttpSessionStorage excluded = new FileHttpSessionStorage(dir, Collections.singleton("secret"));
        try {
            HttpSessionData data = excluded.createSession();
            data.setMaxInactiveInterval(3600);
            data.setAttribute("secret", new SerializableValue("s"));
            data.setAttribute("keep", new SerializableValue("k"));
            excluded.saveSession(data);
            String id = data.getId();
            excluded.shutdown();

            FileHttpSessionStorage restarted = new FileHttpSessionStorage(dir, Collections.singleton("secret"));
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
        Files.write(bad, new byte[] { 1, 2, 3, 4, 5 });

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

        assertFalse(Files.exists(tempDir.resolve(data.getId() + ".session")), "已失效会话不应写入磁盘");
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

    /**
     * 反序列化过滤：同一份**格式正确**的文件，不加过滤器会载入（见 {@link #restart_restoresNonExpiredSession()}）， 加了拒绝一切的过滤器则走既有坏文件容错路径（丢弃 +
     * 删除）。两半合起来才说明「默认不变、收紧有效」。
     */
    @Test
    void deserializationFilter_discardsWellFormedFileThatWouldOtherwiseLoad() throws Exception {
        HttpSessionData data = storage.createSession();
        data.setAttribute("k", "v");
        storage.saveSession(data);
        Path file = tempDir.resolve(data.getId() + ".session");
        assertTrue(Files.exists(file), "落盘应成功");

        FileHttpSessionStorage filtered = new FileHttpSessionStorage(tempDir, Set.of(),
                FileHttpSessionStorage.parseDeserializationFilter("!*"));
        try {
            assertEquals(0, filtered.getActiveSessionCount(), "被过滤器拒绝的文件不应载入");
            assertFalse(Files.exists(file), "被过滤器拒绝的文件应被删除");
        } finally {
            filtered.shutdown();
        }
    }

    /**
     * 过滤器拒绝与「文件损坏」抛出的是同一种异常（{@code InvalidClassException}），只有消息文本不同。 本类不依赖消息文本，而是自行记下被拒的类名 —— 这里锁定该记录逻辑。
     */
    @Test
    void recordingRejections_capturesFirstRejectedClass() {
        AtomicReference<String> rejected = new AtomicReference<>();
        ObjectInputFilter.FilterInfo info = mock(ObjectInputFilter.FilterInfo.class);
        when(info.serialClass()).thenReturn((Class) java.util.HashMap.class);

        ObjectInputFilter wrapper = FileHttpSessionStorage.recordingRejections(i -> ObjectInputFilter.Status.REJECTED,
                rejected);
        assertEquals(ObjectInputFilter.Status.REJECTED, wrapper.checkInput(info));
        assertEquals("java.util.HashMap", rejected.get(), "被拒的类名应被记下（用户据此知道要放行什么）");
    }

    @Test
    void recordingRejections_ignoresAllowedClasses() {
        AtomicReference<String> rejected = new AtomicReference<>();
        ObjectInputFilter.FilterInfo info = mock(ObjectInputFilter.FilterInfo.class);
        when(info.serialClass()).thenReturn((Class) String.class);

        ObjectInputFilter wrapper = FileHttpSessionStorage.recordingRejections(i -> ObjectInputFilter.Status.ALLOWED,
                rejected);
        assertEquals(ObjectInputFilter.Status.ALLOWED, wrapper.checkInput(info));
        assertNull(rejected.get(), "放行时不该留下被拒记录");
    }

    @Test
    void recordingRejections_unspecifiedStatus_leavesRecordUntouched() {
        AtomicReference<String> rejected = new AtomicReference<>();
        ObjectInputFilter.FilterInfo info = mock(ObjectInputFilter.FilterInfo.class);
        when(info.serialClass()).thenReturn((Class) String.class);

        ObjectInputFilter wrapper = FileHttpSessionStorage.recordingRejections(i -> ObjectInputFilter.Status.UNDECIDED,
                rejected);
        assertEquals(ObjectInputFilter.Status.UNDECIDED, wrapper.checkInput(info));
        assertNull(rejected.get(), "只有 REJECTED 才算被拒");
    }

    /**
     * 启动清扫只删「足够旧」的 {@code *.tmp}：崩溃遗留的 tmp 不再永久累积，而**另一实例**正在写的 tmp（刚创建、 mtime 很新）必须留下 —— 否则共享 store-dir 的两个实例会互相破坏落盘。
     */
    @Test
    void startup_sweepsStaleTmpButKeepsFreshTmp() throws Exception {
        Path stale = tempDir.resolve("deadbeef.tmp");
        Files.write(stale, new byte[] { 1, 2, 3 });
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 2 * 3_600_000L));
        Path fresh = tempDir.resolve("cafebabe.tmp");
        Files.write(fresh, new byte[] { 4, 5, 6 });

        FileHttpSessionStorage restarted = new FileHttpSessionStorage(tempDir);
        try {
            assertFalse(Files.exists(stale), "超过年龄阈值的遗留 tmp 应被清扫");
            assertTrue(Files.exists(fresh), "刚创建的 tmp 可能属于另一个实例，不能被删");
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void deserializationFilter_blankMeansNoFiltering() {
        assertNull(FileHttpSessionStorage.parseDeserializationFilter(null), "未配置 = 不过滤（默认不变）");
        assertNull(FileHttpSessionStorage.parseDeserializationFilter("   "), "全空白 = 不过滤");
        assertNotNull(FileHttpSessionStorage.parseDeserializationFilter("java.util.*;!*"));
    }

    /**
     * 同一会话并发落盘：文件内容必须是某一次<b>完整</b>写入的结果，不得出现半写/相互截断。
     * <p>
     * 修复前「写 tmp → ATOMIC_MOVE」两步无互斥，且 tmp 名固定为 {@code <id>.tmp}：并发 save 会交错覆盖同一个临时文件。
     * </p>
     */
    @Test
    void saveSession_concurrentWrites_keepsFileConsistent() throws Exception {
        HttpSessionData data = storage.createSession();
        Set<String> written = ConcurrentHashMap.newKeySet();
        int n = 8;
        runConcurrently(n, () -> {
            String value = Thread.currentThread().getName();
            written.add(value);
            data.setAttribute("k", value);
            storage.saveSession(data);
        });

        Path file = tempDir.resolve(data.getId() + ".session");
        assertTrue(Files.exists(file), "落盘应成功");

        // 另开一个实例读盘：文件必须能被完整反序列化，且内容恰好是某个写线程的那一次完整写入
        FileHttpSessionStorage reloaded = new FileHttpSessionStorage(tempDir);
        try {
            HttpSessionData restored = reloaded.getSession(data.getId());
            assertNotNull(restored, "磁盘上的会话应可被完整反序列化");
            assertTrue(written.contains(String.valueOf(restored.getAttribute("k"))), "落盘内容应是某次完整写入，而非半写或交错结果");
        } finally {
            reloaded.shutdown();
        }
        try (Stream<Path> files = Files.list(tempDir)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")), "并发写不应残留 tmp 文件");
        }
    }

    /**
     * invalidate 的落点 {@code removeSession} 与并发 save 竞态：文件不得被写回 —— 否则失效会话在重启后被恢复（会话复活）。
     * <p>
     * 两种交错都以「文件不存在」收束：remove 先持锁时 save 取锁后重查内存发现会话已摘除而放弃； save 先持锁时写完后被紧随的 remove 删掉。
     * </p>
     */
    @Test
    void removeSession_racingWithSave_doesNotResurrectSession() throws Exception {
        HttpSessionData data = storage.createSession();
        data.setAttribute("k", "v");
        storage.saveSession(data);
        Path file = tempDir.resolve(data.getId() + ".session");
        assertTrue(Files.exists(file), "前置：落盘应成功");

        CountDownLatch start = new CountDownLatch(1);
        int rounds = 200;
        CountDownLatch done = new CountDownLatch(2);
        Thread saver = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < rounds; i++) {
                    storage.saveSession(data);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        Thread remover = new Thread(() -> {
            try {
                start.await();
                storage.removeSession(data.getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        saver.start();
        remover.start();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "竞态任务未在时限内结束");

        assertFalse(Files.exists(file), "已失效的会话不得被并发落盘写回");
        assertNull(storage.getSession(data.getId()), "会话不应在内存中复活");
    }

    /**
     * 过期扫除的原子性契约：{@code sweepExpired} 摘除一个过期会话时，**必须同时删掉它的文件**； 未过期的会话则两者都不动。
     * <p>
     * 起因是一次审查：旧实现用 {@code sessions.values().removeIf(...)}，把「删文件」放在写锁内、 「摘除内存条目」交给 CHM 在谓词返回后才做。两步分离产生的窗口里，并发
     * {@code saveSession} 能通过 {@code !sessions.containsKey(id)} 守卫（过期会话不属于 {@code isInvalid()}）把刚删掉的 文件写回，重启时被
     * {@code loadExistingSessions} 当未过期会话加载 —— <b>过期会话复活</b>。 修复把「条件移除 + 删文件」收进同一把写锁。
     * </p>
     * <p>
     * 此处断言的是修复所保证的<b>确定性契约</b>（摘除 ⇒ 文件已删），而非去搏那个极窄的并发窗口 —— 后者需要精确的调度控制，写成随机并发只会得到 flaky 的测试。
     * </p>
     */
    @Test
    void sweepExpired_removesEntryAndFile_together() throws Exception {
        HttpSessionData expired = storage.createSession();
        expired.setMaxInactiveInterval(1);
        expired.setLastAccessedTime(System.currentTimeMillis() - 10_000L);
        expired.setAttribute("v", new SerializableValue("dead"));
        storage.saveSession(expired);

        HttpSessionData alive = storage.createSession();
        alive.setMaxInactiveInterval(3600);
        alive.setAttribute("v", new SerializableValue("live"));
        storage.saveSession(alive);

        Path expiredFile = tempDir.resolve(expired.getId() + ".session");
        Path aliveFile = tempDir.resolve(alive.getId() + ".session");
        assertTrue(Files.exists(expiredFile));
        assertTrue(Files.exists(aliveFile));

        storage.sweepExpired(System.currentTimeMillis());

        // 过期：内存与磁盘一起消失
        assertNull(storage.getSession(expired.getId()), "过期会话应从内存摘除");
        assertFalse(Files.exists(expiredFile), "过期会话的文件应同时删除（否则重启复活）");
        // 未过期：两者都保留
        assertNotNull(storage.getSession(alive.getId()), "未过期会话不应被摘除");
        assertTrue(Files.exists(aliveFile), "未过期会话的文件不应被删");
    }

    /** 起 n 个线程同时执行 action，全部结束后才返回。 */
    private static void runConcurrently(int n, Runnable action) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    action.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务未在时限内结束");
    }
}
