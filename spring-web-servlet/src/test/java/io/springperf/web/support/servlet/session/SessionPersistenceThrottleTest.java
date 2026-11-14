package io.springperf.web.support.servlet.session;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话持久化脏检查 + 访问时间节流的回归测试（H2）。
 * <p>
 * 覆盖：内容变更必落盘、纯读请求跳过落盘、访问时间超窗口后落盘、写盘期间并发属性写不丢失。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SessionPersistenceThrottleTest {

    @Mock
    WebContext webContext;
    @Mock
    ApplicationProperties props;

    /** 统计 saveSession 调用次数、可阻塞的阻塞型存储。 */
    private static final class CountingBlockingStorage implements HttpSessionStorage {
        final AtomicInteger saveAttempts = new AtomicInteger();
        final CountDownLatch saved = new CountDownLatch(1);
        volatile boolean failOnSave;

        @Override
        public HttpSessionData getSession(String sessionId) {
            return null;
        }

        @Override
        public HttpSessionData createSession() {
            return new HttpSessionData("id-" + System.nanoTime(), System.currentTimeMillis());
        }

        @Override
        public void saveSession(HttpSessionData session) {
            saveAttempts.incrementAndGet();
            saved.countDown();
            if (failOnSave) {
                throw new RuntimeException("simulated IO failure");
            }
        }

        @Override
        public void removeSession(String sessionId) {
        }

        @Override
        public boolean hasBlockingSave() {
            return true;
        }
    }

    private CountingBlockingStorage storage;
    private PerfHttpSessionManager manager;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(webContext.getCtx()).thenReturn(mock(ApplicationContext.class));
        lenient().when(props.get(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(props.getBoolean(any(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(props.getDurationSeconds(any(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        storage = new CountingBlockingStorage();
        when(webContext.getBeanFromCtx(HttpSessionStorage.class)).thenReturn(storage);
        manager = new PerfHttpSessionManager();
        manager.initWithWebContext(webContext);
    }

    /** 等待后台保存线程执行完至少 n 次保存（轮询，上限 2s）。 */
    private void awaitSaves(int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (storage.saveAttempts.get() >= n) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("期望至少 " + n + " 次保存，实际 " + storage.saveAttempts.get());
    }

    @Test
    void firstResponse_savesEvenWithoutAttributeChange() throws Exception {
        // 新会话 persistedVersion=-1、contentVersion=0：version != persisted，首次响应必须落盘
        // （否则重启恢复会丢失整个会话）。这条用例锁定「首次必存」语义。
        try {
            PerfHttpSession session = manager.createSession();
            manager.saveSessionAfterResponse(session);
            assertTrue(storage.saved.await(2, TimeUnit.SECONDS), "首次响应应触发落盘");
            assertEquals(1, storage.saveAttempts.get());
        } finally {
            manager.destroyComponent();
        }
    }

    @Test
    void pureReadRequest_withinWindow_skipsSave() throws Exception {
        // 首次保存完成后，标记 persisted；随后若干次「仅 markAccessed、无属性变更、未到窗口」的请求
        // 应跳过序列化与文件 IO。用 markPersisted 把访问时间设为当前，模拟窗口刚开始。
        try {
            PerfHttpSession session = manager.createSession();
            HttpSessionData data = session.getData();
            long version = data.getContentVersion();
            data.markPersisted(version, System.currentTimeMillis()); // 模拟首次已落盘

            manager.saveSessionAfterResponse(session);
            manager.saveSessionAfterResponse(session);
            manager.saveSessionAfterResponse(session);

            Thread.sleep(200L); // 给后台线程可能的调度留出观察窗口
            assertEquals(0, storage.saveAttempts.get(), "纯读请求且处于节流窗口内不应触发落盘");
        } finally {
            manager.destroyComponent();
        }
    }

    @Test
    void attributeChange_triggersImmediateSave() throws Exception {
        // 属性变更使 contentVersion 前进，即使仍在访问时间窗口内也必须立即落盘
        try {
            PerfHttpSession session = manager.createSession();
            HttpSessionData data = session.getData();
            data.markPersisted(data.getContentVersion(), System.currentTimeMillis());
            assertEquals(0, storage.saveAttempts.get());

            session.setAttribute("user", "alice");

            manager.saveSessionAfterResponse(session);
            awaitSaves(1);
            assertEquals(1, storage.saveAttempts.get(), "内容变更应立即触发落盘，不受节流窗口限制");
        } finally {
            manager.destroyComponent();
        }
    }

    @Test
    void accessTimeBeyondWindow_triggersSave() throws Exception {
        // 无属性变更，但上次落盘访问时间已超出节流窗口（模拟 61 秒前），应触发一次落盘刷新 lastAccessedTime
        try {
            PerfHttpSession session = manager.createSession();
            HttpSessionData data = session.getData();
            data.markPersisted(data.getContentVersion(), System.currentTimeMillis() - 61_000L);

            manager.saveSessionAfterResponse(session);
            awaitSaves(1);
            assertEquals(1, storage.saveAttempts.get(), "访问时间超窗口应触发一次落盘");
        } finally {
            manager.destroyComponent();
        }
    }

    @Test
    void concurrentWriteDuringSerialization_notLostOnNextSave() throws Exception {
        // 用 FileHttpSessionStorage 的 markPersisted 语义验证：版本快照回填时若 contentVersion 已前进，
        // persistedVersion 不推进 → 下次请求仍能识别为脏并再落盘一次，覆盖写盘期间的新写入。
        HttpSessionData data = new HttpSessionData("concurrent", System.currentTimeMillis());
        long snapshot = data.getContentVersion(); // 0

        // 模拟序列化进行中，业务线程写入属性，version 前进到 1
        data.setAttribute("k", "v");
        assertEquals(snapshot + 1, data.getContentVersion());

        data.markPersisted(snapshot, System.currentTimeMillis());
        // 版本已前进 → persistedVersion 不应推进到 snapshot
        assertTrue(data.getContentVersion() != data.getPersistedVersion(),
                "写盘期间有新写入时，回填不应推进 persistedVersion，否则并发变更会丢失");
    }

    @Test
    void readObjectResetsTransientDirtyState() throws Exception {
        // 反序列化后 transient 脏检查字段必须重建，否则 contentVersion 为 null 会 NPE，
        // 且 persistedVersion 应回到 -1 触发重启后首次重落盘。
        HttpSessionData original = new HttpSessionData("ser", System.currentTimeMillis());
        original.setAttribute("k", "v");
        original.markPersisted(original.getContentVersion(), System.currentTimeMillis());

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
            oos.writeObject(original);
        }
        HttpSessionData restored;
        try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bos.toByteArray()))) {
            restored = (HttpSessionData) ois.readObject();
        }

        assertEquals(-1, restored.getPersistedVersion(), "反序列化后应视为未落盘");
        assertEquals(Long.MIN_VALUE, restored.getPersistedAccessTime());
        // 写操作不应因 transient 字段为 null 而抛 NPE
        restored.setAttribute("k2", "v2");
        assertEquals(1, restored.getContentVersion());
    }
}
