package io.springperf.web.support.servlet.session;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfHttpSessionTest {

    @Mock
    ServletContext servletContext;

    private HttpSessionData data;
    private PerfHttpSession session;

    @BeforeEach
    void setUp() {
        data = new HttpSessionData("test-session-id", 1000L);
        session = new PerfHttpSession(data, servletContext);
    }

    @Test
    void getId() {
        assertEquals("test-session-id", session.getId());
    }

    @Test
    void getCreationTime() {
        assertEquals(1000L, session.getCreationTime());
    }

    @Test
    void getLastAccessedTime() {
        assertEquals(1000L, session.getLastAccessedTime());
    }

    @Test
    void getServletContext() {
        assertSame(servletContext, session.getServletContext());
    }

    @Test
    void setMaxInactiveInterval() {
        session.setMaxInactiveInterval(3600);
        assertEquals(3600, session.getMaxInactiveInterval());
    }

    @Test
    void isNew() {
        assertTrue(session.isNew());
    }

    @Test
    void isNew_afterSetNotNew() {
        session.setNotNew();
        assertFalse(session.isNew());
    }

    @Test
    void setAttribute_getAttribute() {
        session.setAttribute("key", "value");
        assertEquals("value", session.getAttribute("key"));
    }

    @Test
    void setAttribute_replace() {
        session.setAttribute("key", "value1");
        session.setAttribute("key", "value2");
        assertEquals("value2", session.getAttribute("key"));
    }

    @Test
    void removeAttribute() {
        session.setAttribute("key", "value");
        session.removeAttribute("key");
        assertNull(session.getAttribute("key"));
    }

    @Test
    void removeAttribute_notExists_doesNothing() {
        session.setAttribute("keep", "value");

        session.removeAttribute("nonexistent");

        assertEquals("value", session.getAttribute("keep"), "移除不存在的属性不得影响其它属性");
    }

    @Test
    void setAttribute_null_removesAttribute() {
        // Servlet 规范 §7.5：null 值等价于 removeAttribute（修复前 ConcurrentHashMap.put(k, null) 抛 NPE）
        session.setAttribute("key", "value");
        session.setAttribute("key", null);
        assertNull(session.getAttribute("key"));
    }

    @Test
    void setAttribute_null_whenAbsent_doesNotThrow() {
        assertDoesNotThrow(() -> session.setAttribute("missing", null));
        assertNull(session.getAttribute("missing"));
    }

    @Test
    void setAttribute_null_withBindingListener_callsValueUnbound() {
        TestBindingListener listener = new TestBindingListener();
        session.setAttribute("key", listener);
        session.setAttribute("key", null);
        assertTrue(listener.unbound, "置 null 等同于移除，应触发 valueUnbound");
        assertEquals("key", listener.unboundName);
    }

    @Test
    void getAttributeNames() {
        session.setAttribute("k1", "v1");
        session.setAttribute("k2", "v2");
        List<String> names = new ArrayList<>();
        session.getAttributeNames().asIterator().forEachRemaining(names::add);
        assertTrue(names.contains("k1"));
        assertTrue(names.contains("k2"));
        assertEquals(2, names.size());
    }

    @Test
    void invalidate_clearsAttributes() {
        session.setAttribute("key", "value");
        session.invalidate();
        assertTrue(session.isInvalid());
        // 只断言 getAttribute 抛异常只能证明「已失效」，必须直接查底层属性表是否真的清空
        assertNull(data.getAttribute("key"), "invalidate 必须清空底层属性表");
        assertThrows(IllegalStateException.class, () -> session.getAttribute("key"));
    }

    @Test
    void invalidate_afterInvalidate_throws() {
        session.invalidate();
        assertThrows(IllegalStateException.class, () -> session.getAttribute("key"));
        assertThrows(IllegalStateException.class, () -> session.setAttribute("key", "value"));
        assertThrows(IllegalStateException.class, () -> session.removeAttribute("key"));
        assertThrows(IllegalStateException.class, () -> session.getId());
        assertThrows(IllegalStateException.class, () -> session.getCreationTime());
        assertThrows(IllegalStateException.class, () -> session.isNew());
    }

    // ===================== HttpSessionBindingListener =====================

    @Test
    void setAttribute_withBindingListener_callsValueBound() {
        TestBindingListener listener = new TestBindingListener();
        session.setAttribute("key", listener);
        assertTrue(listener.bound);
        assertEquals("key", listener.boundName);
    }

    @Test
    void setAttribute_replaceBindingListener_callsValueUnboundOnOld() {
        TestBindingListener oldListener = new TestBindingListener();
        TestBindingListener newListener = new TestBindingListener();
        session.setAttribute("key", oldListener);
        session.setAttribute("key", newListener);
        assertTrue(oldListener.unbound);
        assertTrue(newListener.bound);
    }

    @Test
    void removeAttribute_withBindingListener_callsValueUnbound() {
        TestBindingListener listener = new TestBindingListener();
        session.setAttribute("key", listener);
        session.removeAttribute("key");
        assertTrue(listener.unbound);
        assertEquals("key", listener.unboundName);
    }

    @Test
    void removeAttribute_nonBindingListener_doesNotCallValueUnbound() {
        TestBindingListener listener = new TestBindingListener();
        session.setAttribute("bound", listener);
        session.setAttribute("key", "plain-value");

        session.removeAttribute("key");

        assertNull(session.getAttribute("key"), "普通属性应被移除");
        assertFalse(listener.unbound, "移除普通值不得触发其它属性的 valueUnbound");
        assertSame(listener, session.getAttribute("bound"));
    }

    @Test
    void invalidate_callsValueUnboundOnAllAttributes() {
        TestBindingListener l1 = new TestBindingListener();
        TestBindingListener l2 = new TestBindingListener();
        session.setAttribute("k1", l1);
        session.setAttribute("k2", l2);
        session.invalidate();
        assertTrue(l1.unbound);
        assertTrue(l2.unbound);
    }

    @Test
    void invalidate_nonBindingListener_doesNotCallValueUnbound() {
        session.setAttribute("k1", "plain");

        session.invalidate();

        assertTrue(session.isInvalid(), "invalidate 应标记会话失效");
        assertNull(data.getAttribute("k1"), "invalidate 应清空普通属性");
        assertThrows(IllegalStateException.class, () -> session.getAttribute("k1"));
    }

    // ===================== Listeners =====================

    @Test
    void setAttribute_firesAttributeAddedListener() {
        HttpSessionAttributeListener attrListener = mock(HttpSessionAttributeListener.class);
        List<HttpSessionAttributeListener> attrListeners = new ArrayList<>();
        attrListeners.add(attrListener);
        session = new PerfHttpSession(data, servletContext, new ArrayList<>(), attrListeners);
        session.setAttribute("key", "value");
        ArgumentCaptor<HttpSessionBindingEvent> captor = ArgumentCaptor.forClass(HttpSessionBindingEvent.class);
        verify(attrListener).attributeAdded(captor.capture());
        HttpSessionBindingEvent event = captor.getValue();
        assertEquals("key", event.getName());
        assertEquals("value", event.getValue());
        assertSame(session, event.getSession(), "事件必须携带触发它的会话实例");
    }

    @Test
    void setAttribute_firesAttributeReplacedListener() {
        HttpSessionAttributeListener attrListener = mock(HttpSessionAttributeListener.class);
        List<HttpSessionAttributeListener> attrListeners = new ArrayList<>();
        attrListeners.add(attrListener);
        session = new PerfHttpSession(data, servletContext, new ArrayList<>(), attrListeners);
        session.setAttribute("key", "old");
        session.setAttribute("key", "new");
        ArgumentCaptor<HttpSessionBindingEvent> captor = ArgumentCaptor.forClass(HttpSessionBindingEvent.class);
        verify(attrListener).attributeReplaced(captor.capture());
        HttpSessionBindingEvent event = captor.getValue();
        assertEquals("key", event.getName());
        // Servlet 规范：attributeReplaced 事件必须携带被替换的旧值，传成新值说明实现有误
        assertEquals("old", event.getValue(), "attributeReplaced 必须携带旧值");
        assertSame(session, event.getSession());
    }

    @Test
    void removeAttribute_firesAttributeRemovedListener() {
        HttpSessionAttributeListener attrListener = mock(HttpSessionAttributeListener.class);
        List<HttpSessionAttributeListener> attrListeners = new ArrayList<>();
        attrListeners.add(attrListener);
        session = new PerfHttpSession(data, servletContext, new ArrayList<>(), attrListeners);
        session.setAttribute("key", "value");
        session.removeAttribute("key");
        ArgumentCaptor<HttpSessionBindingEvent> captor = ArgumentCaptor.forClass(HttpSessionBindingEvent.class);
        verify(attrListener).attributeRemoved(captor.capture());
        HttpSessionBindingEvent event = captor.getValue();
        assertEquals("key", event.getName());
        assertEquals("value", event.getValue(), "attributeRemoved 必须携带被移除的旧值");
        assertSame(session, event.getSession());
    }

    @Test
    void invalidate_firesSessionDestroyedListener() {
        HttpSessionListener sessionListener = mock(HttpSessionListener.class);
        List<HttpSessionListener> sessionListeners = new ArrayList<>();
        sessionListeners.add(sessionListener);
        session = new PerfHttpSession(data, servletContext, sessionListeners, new ArrayList<>());
        session.invalidate();
        ArgumentCaptor<HttpSessionEvent> captor = ArgumentCaptor.forClass(HttpSessionEvent.class);
        verify(sessionListener).sessionDestroyed(captor.capture());
        assertSame(session, captor.getValue().getSession(), "sessionDestroyed 必须携带被销毁的会话实例");
    }

    @Test
    void invalidate_firesOnInvalidateCallback() {
        Runnable callback = mock(Runnable.class);
        session.setOnInvalidateCallback(callback);
        session.invalidate();
        verify(callback).run();
    }

    @Test
    void markAccessed_updatesLastAccessedTime() {
        long before = System.currentTimeMillis();
        session.markAccessed();
        long accessed = session.getLastAccessedTime();

        assertTrue(accessed >= before && accessed <= System.currentTimeMillis(),
                "markAccessed 应把最后访问时间更新为当前墙钟时间（" + accessed + "）");
        assertNotEquals(data.getCreationTime(), accessed, "不得复用会话创建时间");
    }

    @Test
    void getData_returnsData() {
        assertSame(data, session.getData());
    }

    @Test
    void isInvalid_afterInvalidate() {
        assertFalse(session.isInvalid());
        session.invalidate();
        assertTrue(session.isInvalid());
    }

    @Test
    void invalidate_sharedAcrossConcurrentWrappers_preventsRevival() {
        // L8：同一底层 data 的多个并发 wrapper，A 失效后 B 也应看到失效状态，
        // 否则失效会话可被另一在途请求复活/重新持久化。
        PerfHttpSession a = new PerfHttpSession(data, servletContext);
        PerfHttpSession b = new PerfHttpSession(data, servletContext);
        a.invalidate();
        assertTrue(b.isInvalid(), "B 的 wrapper 应看到共享 data 的失效状态");
        assertThrows(IllegalStateException.class, () -> b.getAttribute("k"), "失效会话的并发 wrapper 不应再可读属性");
        assertThrows(IllegalStateException.class, () -> b.setAttribute("k", "v"), "失效会话的并发 wrapper 不应再可写属性");
    }

    @Test
    void tryInvalidate_grantsExactlyOnce() {
        assertTrue(data.tryInvalidate(), "首次抢占应成功");
        assertFalse(data.tryInvalidate(), "重复抢占必须失败，否则会重复触发销毁侧效应");
        assertFalse(data.tryInvalidate(), "重复抢占必须持续失败");
        assertTrue(data.isInvalid());
    }

    @Test
    void invalidate_secondCallThrowsAndDoesNotRefireDestroy() {
        AtomicInteger destroyed = new AtomicInteger();
        List<HttpSessionListener> listeners = new ArrayList<>();
        listeners.add(new CountingSessionListener(destroyed));
        PerfHttpSession s = new PerfHttpSession(data, servletContext, listeners, new ArrayList<>());
        s.setOnInvalidateCallback(destroyed::incrementAndGet);

        s.invalidate();
        assertThrows(IllegalStateException.class, s::invalidate, "重复 invalidate 应抛 IllegalStateException");

        assertEquals(2, destroyed.get(), "sessionDestroyed 与 onInvalidateCallback 各应恰好触发一次");
    }

    @Test
    void invalidate_concurrent_onlyOneRunsDestruction() throws Exception {
        // 幂等性回归：并发 invalidate 时销毁侧效应（onInvalidateCallback + sessionDestroyed）必须各只发生一次。
        // SlowIsInvalidData 在「读取失效状态」这一步引入微小延迟来放大 check-then-act 窗口；
        // 修复后的实现走 tryInvalidate() 原子占位，根本不调用 isInvalid()，故该钩子对修复后的代码无作用，
        // 不会把测试变成依赖 sleeps 的脆弱用例。多轮 + 栅栏起跑使漏检概率趋近于 0。
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        java.util.Queue<Throwable> unexpectedFailures = new java.util.concurrent.ConcurrentLinkedQueue<>();
        try {
            for (int round = 0; round < 200; round++) {
                SlowIsInvalidData slowData = new SlowIsInvalidData("session-" + round, 1000L);
                AtomicInteger destroyed = new AtomicInteger();
                List<HttpSessionListener> listeners = new ArrayList<>();
                listeners.add(new CountingSessionListener(destroyed));
                PerfHttpSession s = new PerfHttpSession(slowData, servletContext, listeners, new ArrayList<>());
                s.setOnInvalidateCallback(destroyed::incrementAndGet);

                CyclicBarrier start = new CyclicBarrier(threads);
                CountDownLatch done = new CountDownLatch(threads);
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            try {
                                s.invalidate();
                            } catch (IllegalStateException expected) {
                                // Servlet 规范语义：已被其它线程失效的会话重复 invalidate 抛异常
                            }
                        } catch (Throwable t) {
                            unexpectedFailures.add(t);
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertTrue(done.await(10, TimeUnit.SECONDS), "round " + round + ": 并发 invalidate 未在超时前完成");
                assertTrue(unexpectedFailures.isEmpty(),
                        "round " + round + ": 并发 invalidate 出现非预期异常 " + unexpectedFailures);
                assertEquals(2, destroyed.get(),
                        "round " + round + ": 并发 invalidate 下 sessionDestroyed 与 onInvalidateCallback 各应恰好触发一次");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    static class CountingSessionListener implements HttpSessionListener {
        private final AtomicInteger destroyed;

        CountingSessionListener(AtomicInteger destroyed) {
            this.destroyed = destroyed;
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent se) {
            destroyed.incrementAndGet();
        }
    }

    // ===================== Test helper =====================

    /**
     * 测试替身：在「读取失效状态」这一步让出一小段时间，以放大 check-then-act 的窗口，使竞态在 修复前的实现下几乎必然复现。修复后的实现不再读取该状态来判断是否失效，故本替身不参与其路径
     * （钩子对修复后代码无副作用，不会让本测试退化成依赖 sleep 的脆弱用例）。
     */
    static class SlowIsInvalidData extends HttpSessionData {
        SlowIsInvalidData(String id, long creationTime) {
            super(id, creationTime);
        }

        @Override
        public boolean isInvalid() {
            LockSupport.parkNanos(50_000L);
            return super.isInvalid();
        }
    }

    static class TestBindingListener implements HttpSessionBindingListener {
        boolean bound;
        boolean unbound;
        String boundName;
        String unboundName;

        @Override
        public void valueBound(HttpSessionBindingEvent event) {
            bound = true;
            boundName = event.getName();
        }

        @Override
        public void valueUnbound(HttpSessionBindingEvent event) {
            unbound = true;
            unboundName = event.getName();
        }
    }
}
