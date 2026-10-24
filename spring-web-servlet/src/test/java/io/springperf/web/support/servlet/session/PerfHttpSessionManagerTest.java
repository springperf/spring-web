package io.springperf.web.support.servlet.session;

import io.springperf.web.context.WebContext;
import io.springperf.web.support.servlet.Authenticator;
import io.springperf.web.support.servlet.context.PerfServletContext;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfHttpSessionManagerTest {

    @Mock
    WebContext webContext;
    @Mock
    io.springperf.web.context.ApplicationProperties props;

    private PerfHttpSessionManager manager;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(webContext.getCtx()).thenReturn(mock(org.springframework.context.ApplicationContext.class));
        lenient().when(props.get(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        // 兜底：未显式桩化的 getBoolean 返回传入默认值（如 persistent 默认 false、cookie.secure 默认 false）
        lenient().when(props.getBoolean(any(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
        // 兜底：未配置 session.timeout 时 getDurationSeconds 返回默认值 1800s（30 分钟）
        lenient().when(props.getDurationSeconds(any(), anyLong())).thenAnswer(invocation -> invocation.getArgument(1));
        manager = new PerfHttpSessionManager();
        manager.initWithWebContext(webContext);
    }

    @Test
    void getServletContext_returnsPerfServletContext() {
        assertNotNull(manager.getServletContext());
        assertInstanceOf(PerfServletContext.class, manager.getServletContext());
    }

    @Test
    void initWithWebContext_reusesRegisteredServletContext() {
        // ServletContext 已作为独立组件注册时，session 管理器应直接复用，不再新建
        PerfServletContext registered = mock(PerfServletContext.class);
        when(webContext.getWebComponent(PerfServletContext.class)).thenReturn(registered);
        // 清除 @BeforeEach 中 manager.initWithWebContext 产生的历史调用，只统计本测试方法内的行为
        clearInvocations(webContext);

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);

        assertSame(registered, newManager.getServletContext());
        verify(webContext, never()).registerWebComponent(any(PerfServletContext.class));
    }

    @Test
    void createSession_returnsSession() {
        PerfHttpSession session = manager.createSession();
        assertNotNull(session);
        assertNotNull(session.getId());
        assertTrue(session.isNew());
    }

    @Test
    void createSession_appliesSessionTimeoutToMaxInactiveInterval() {
        // P0 回归：新 session 必须带上非零 maxInactiveInterval（默认 30 分钟），
        // 否则 InMemoryHttpSessionStorage.isExpired() 恒为 false，session 永不过期 → 无界内存增长。
        PerfHttpSession session = manager.createSession();
        assertTrue(session.getMaxInactiveInterval() > 0);
        assertEquals(30 * 60, session.getMaxInactiveInterval());
    }

    @Test
    void getSession_newSession_returnsNull() {
        assertNull(manager.getSession("nonexistent"));
    }

    @Test
    void createSession_thenGetSession_returnsExisting() {
        PerfHttpSession created = manager.createSession();
        PerfHttpSession retrieved = manager.getSession(created.getId());
        assertNotNull(retrieved);
        assertEquals(created.getId(), retrieved.getId());
        assertFalse(retrieved.isNew());
    }

    @Test
    void removeSession_removesFromStorage() {
        PerfHttpSession session = manager.createSession();
        assertNotNull(manager.getSession(session.getId()));
        manager.removeSession(session.getId());
        assertNull(manager.getSession(session.getId()));
    }

    @Test
    void saveSession_persistsData() {
        PerfHttpSession session = manager.createSession();
        session.setAttribute("key", "value");
        manager.saveSession(session);
        PerfHttpSession retrieved = manager.getSession(session.getId());
        assertEquals("value", retrieved.getAttribute("key"));
    }

    /* ==================== 响应结束后的保存：阻塞型存储不得占用 EventLoop ==================== */

    /** 模拟文件/远程存储：保存是阻塞的，且记录执行线程。 */
    private static final class BlockingStorage implements HttpSessionStorage {
        final java.util.concurrent.ConcurrentMap<String, HttpSessionData> sessions = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicReference<Thread> saveThread = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch saved = new java.util.concurrent.CountDownLatch(1);
        final AtomicInteger saveAttempts = new AtomicInteger();
        /** 置 true 时保存抛异常，用于验证后台保存失败不扩散到请求线程/后续保存。 */
        volatile boolean failOnSave;

        @Override
        public HttpSessionData getSession(String sessionId) {
            return sessions.get(sessionId);
        }

        @Override
        public HttpSessionData createSession() {
            HttpSessionData data = new HttpSessionData("s-" + System.nanoTime(), System.currentTimeMillis());
            sessions.put(data.getId(), data);
            return data;
        }

        @Override
        public void saveSession(HttpSessionData session) {
            saveAttempts.incrementAndGet();
            saveThread.set(Thread.currentThread());
            if (failOnSave) {
                // 先放行等待方，再抛：让测试能确定"本次保存确实被执行过并失败"
                saved.countDown();
                throw new IllegalStateException("simulated storage failure");
            }
            sessions.put(session.getId(), session);
            saved.countDown();
        }

        @Override
        public void removeSession(String sessionId) {
            sessions.remove(sessionId);
        }

        @Override
        public boolean hasBlockingSave() {
            return true;
        }
    }

    private PerfHttpSessionManager newManagerWithStorage(HttpSessionStorage storage) {
        when(webContext.getBeanFromCtx(HttpSessionStorage.class)).thenReturn(storage);
        PerfHttpSessionManager blockingManager = new PerfHttpSessionManager();
        blockingManager.initWithWebContext(webContext);
        return blockingManager;
    }

    @Test
    void saveSessionAfterResponse_blockingStorage_runsOffCallerThread() throws Exception {
        BlockingStorage storage = new BlockingStorage();
        PerfHttpSessionManager blockingManager = newManagerWithStorage(storage);
        try {
            PerfHttpSession session = blockingManager.createSession();
            Thread caller = Thread.currentThread();

            blockingManager.saveSessionAfterResponse(session);

            assertTrue(storage.saved.await(2, TimeUnit.SECONDS), "保存应最终执行");
            assertNotSame(caller, storage.saveThread.get(), "阻塞型存储的保存不得占用调用线程（EventLoop）");
            assertEquals("session-save", storage.saveThread.get().getName());
        } finally {
            blockingManager.destroyComponent();
        }
    }

    @Test
    void saveSessionAfterResponse_invalidOrNullSession_skipped() throws Exception {
        BlockingStorage storage = new BlockingStorage();
        PerfHttpSessionManager blockingManager = newManagerWithStorage(storage);
        try {
            blockingManager.saveSessionAfterResponse(null);

            PerfHttpSession session = blockingManager.createSession();
            session.invalidate();
            blockingManager.saveSessionAfterResponse(session);

            assertFalse(storage.saved.await(200, TimeUnit.MILLISECONDS), "失效会话不得再落盘");
        } finally {
            blockingManager.destroyComponent();
        }
    }

    @Test
    void saveSessionAfterResponse_inMemoryStorage_savesSynchronously() {
        // 内存存储无阻塞 IO：不上线程池，保存立即可见
        PerfHttpSession session = manager.createSession();
        session.setAttribute("k", "v");
        manager.saveSessionAfterResponse(session);
        assertEquals("v", manager.getSession(session.getId()).getAttribute("k"));
    }

    /** 轮询等待条件成立（最长 2s），用于无法用 latch 表达的"后台已执行第 N 次"这类断言。 */
    private static void awaitTrue(String message, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        fail(message);
    }

    @Test
    void saveSessionAfterResponse_storageThrows_failureStaysInBackground() throws Exception {
        // 后台保存抛异常必须被吞在保存任务内：既不能冒到请求线程，也不能让后续保存永远不执行
        BlockingStorage storage = new BlockingStorage();
        PerfHttpSessionManager blockingManager = newManagerWithStorage(storage);
        try {
            storage.failOnSave = true;
            PerfHttpSession session = blockingManager.createSession();

            assertDoesNotThrow(() -> blockingManager.saveSessionAfterResponse(session));
            assertTrue(storage.saved.await(2, TimeUnit.SECONDS), "失败的保存也应已被执行");
            assertEquals("session-save", storage.saveThread.get().getName());

            storage.failOnSave = false;
            blockingManager.saveSessionAfterResponse(session);
            awaitTrue("一次保存失败不应让保存线程池失去后续调度能力", () -> storage.saveAttempts.get() >= 2);
            assertTrue(storage.sessions.containsKey(session.getId()), "后续保存应真正落盘");
        } finally {
            blockingManager.destroyComponent();
        }
    }

    @Test
    void saveSessionAfterResponse_afterDestroy_savesOnCallerThread() throws Exception {
        // 停机流程会关掉保存线程池（saveExecutor=null）。此后仍在途的响应不得丢掉会话数据：
        // 退化为调用线程同步保存（此时已不再有 EventLoop 抢占问题）。
        BlockingStorage storage = new BlockingStorage();
        PerfHttpSessionManager blockingManager = newManagerWithStorage(storage);
        PerfHttpSession session = blockingManager.createSession();
        blockingManager.destroyComponent();

        Thread caller = Thread.currentThread();
        blockingManager.saveSessionAfterResponse(session);

        assertTrue(storage.saved.await(1, TimeUnit.SECONDS), "停机后仍应保存会话");
        assertSame(caller, storage.saveThread.get(), "保存线程池已关闭，应退化到调用线程同步保存");
    }

    @Test
    void changeSessionId_createsNewId() {
        PerfHttpSession oldSession = manager.createSession();
        oldSession.setAttribute("key", "value");
        PerfHttpSession newSession = manager.changeSessionId(oldSession);
        assertNotEquals(oldSession.getId(), newSession.getId());
        assertEquals("value", newSession.getAttribute("key"));
        assertNull(manager.getSession(oldSession.getId()));
    }

    @Test
    void getStorage_returnsInMemoryStorage() {
        assertNotNull(manager.getStorage());
        assertInstanceOf(HttpSessionStorage.class, manager.getStorage());
        // 默认 persistent=false → InMemory 实现
        assertInstanceOf(InMemoryHttpSessionStorage.class, manager.getStorage());
    }

    @Test
    void getCookieName_default() {
        assertEquals("JSESSIONID", manager.getCookieName());
    }

    @Test
    void getSameSite_defaultNull() {
        assertNull(manager.getSameSite());
    }

    @Test
    void getOrder() {
        assertTrue(manager.getOrder() < Integer.MAX_VALUE);
    }

    @Test
    void getSession_null_returnsNull() {
        assertNull(manager.getSession(null));
    }

    @Test
    void getSession_expiredSession_returnsNull() {
        // maxInactiveInterval 由 createSession 写入后，过期判定发生在存储层；
        // manager 必须把"过期即不存在"透传给调用方，而不是返回一个已过期会话。
        PerfHttpSession session = manager.createSession();
        String id = session.getId();
        session.getData().setMaxInactiveInterval(1);
        session.getData().setLastAccessedTime(System.currentTimeMillis() - 100_000L);

        assertNull(manager.getSession(id), "过期会话不应被 manager 返回");
    }

    @Test
    void createSession_negativeServletTimeout_keepsStorageDefault() {
        // ServletContext#getSessionTimeout 返回负值表示"不超时"（Servlet 规范语义）。
        // 此时不得写入负数/0 覆盖存储默认值，避免把"永不超时"误写成某个过期时限。
        PerfServletContext servletCtx = mock(PerfServletContext.class);
        when(servletCtx.getSessionTimeout()).thenReturn(-1);
        when(webContext.getWebComponent(PerfServletContext.class)).thenReturn(servletCtx);

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);
        try {
            assertEquals(0, newManager.createSession().getMaxInactiveInterval(),
                    "负超时（不过期）时 maxInactiveInterval 应保持存储默认值 0");
        } finally {
            assertDoesNotThrow(newManager::destroyComponent);
        }
    }

    @Test
    void initWithWebContext_registersPerfServletContextWhenMissing() {
        // manager 已由 @BeforeEach 初始化，且 webContext 未预注册 PerfServletContext
        verify(webContext).registerWebComponent(any(PerfServletContext.class));
    }

    @Test
    void getAuthenticator_returnsBean() {
        Authenticator bean = mock(Authenticator.class);
        lenient().when(webContext.getBeanFromCtx(Authenticator.class)).thenReturn(bean);

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);
        assertSame(bean, newManager.getAuthenticator());
    }

    @Test
    void getSessionListeners_returnsScannedBeans() {
        HttpSessionListener listener = mock(HttpSessionListener.class);
        org.springframework.context.ApplicationContext ctx = mock(org.springframework.context.ApplicationContext.class);
        lenient().when(ctx.getBeansOfType(HttpSessionListener.class)).thenReturn(Map.of("l", listener));
        when(webContext.getCtx()).thenReturn(ctx);

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);
        assertEquals(1, newManager.getSessionListeners().size());
        assertSame(listener, newManager.getSessionListeners().get(0));
    }

    @Test
    void getAttributeListeners_returnsScannedBeans() {
        HttpSessionAttributeListener listener = mock(HttpSessionAttributeListener.class);
        org.springframework.context.ApplicationContext ctx = mock(org.springframework.context.ApplicationContext.class);
        lenient().when(ctx.getBeansOfType(HttpSessionAttributeListener.class)).thenReturn(Map.of("l", listener));
        when(webContext.getCtx()).thenReturn(ctx);

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);
        assertEquals(1, newManager.getAttributeListeners().size());
        assertSame(listener, newManager.getAttributeListeners().get(0));
    }

    @Test
    void createSession_firesSessionCreatedEvents() {
        AtomicInteger creations = new AtomicInteger();
        HttpSessionListener listener = new HttpSessionListener() {
            @Override
            public void sessionCreated(HttpSessionEvent event) {
                creations.incrementAndGet();
            }
        };
        org.springframework.context.ApplicationContext ctx = mock(org.springframework.context.ApplicationContext.class);
        when(ctx.getBeansOfType(HttpSessionListener.class)).thenReturn(Collections.singletonMap("l", listener));
        when(webContext.getCtx()).thenReturn(ctx);

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);
        newManager.createSession();
        newManager.createSession();

        assertEquals(2, creations.get());
    }

    /* ==================== 运行期动态注册（ServletContext#addListener 的落地入口） ==================== */

    @Test
    void addSessionListener_runtimeRegistration_participatesInDispatch() {
        AtomicInteger creations = new AtomicInteger();
        HttpSessionListener runtimeListener = new HttpSessionListener() {
            @Override
            public void sessionCreated(HttpSessionEvent event) {
                creations.incrementAndGet();
            }
        };

        manager.addSessionListener(runtimeListener);
        // 启动期扫描完成后再注册的监听器必须对后续会话生效（listener 列表是活引用）
        manager.createSession();

        assertSame(runtimeListener, manager.getSessionListeners().get(0));
        assertEquals(1, creations.get(), "运行期注册的 HttpSessionListener 应收到 sessionCreated");
    }

    @Test
    void addAttributeListener_runtimeRegistration_dispatchesAddedReplacedRemoved() {
        AtomicInteger added = new AtomicInteger();
        AtomicInteger replaced = new AtomicInteger();
        AtomicInteger removed = new AtomicInteger();
        HttpSessionAttributeListener runtimeListener = new HttpSessionAttributeListener() {
            @Override
            public void attributeAdded(HttpSessionBindingEvent event) {
                added.incrementAndGet();
            }

            @Override
            public void attributeReplaced(HttpSessionBindingEvent event) {
                replaced.incrementAndGet();
            }

            @Override
            public void attributeRemoved(HttpSessionBindingEvent event) {
                removed.incrementAndGet();
            }
        };

        manager.addAttributeListener(runtimeListener);
        PerfHttpSession session = manager.createSession();
        session.setAttribute("k", "v1");
        session.setAttribute("k", "v2");
        session.removeAttribute("k");

        assertSame(runtimeListener, manager.getAttributeListeners().get(0));
        assertEquals(1, added.get(), "首次绑定应触发 attributeAdded");
        assertEquals(1, replaced.get(), "覆盖同名属性应触发 attributeReplaced");
        assertEquals(1, removed.get(), "移除属性应触发 attributeRemoved");
    }

    @Test
    void listenerGetters_returnUnmodifiableSnapshots() {
        // getter 暴露的是不可变视图：调用方无法绕过 addXxxListener 直接改内部列表
        assertThrows(UnsupportedOperationException.class,
                () -> manager.getSessionListeners().add(mock(HttpSessionListener.class)));
        assertThrows(UnsupportedOperationException.class,
                () -> manager.getAttributeListeners().add(mock(HttpSessionAttributeListener.class)));
        assertThrows(UnsupportedOperationException.class,
                () -> manager.getSessionIdListeners().add(mock(HttpSessionIdListener.class)));
    }

    @Test
    void getSessionIdListeners_defaultEmpty() {
        assertNotNull(manager.getSessionIdListeners());
        assertTrue(manager.getSessionIdListeners().isEmpty(), "未配置 HttpSessionIdListener bean 时列表应为空");
    }

    @Test
    void cookieConfig_customNamePathAndSecure() {
        when(props.get(eq(PerfHttpSessionManager.COOKIE_NAME_KEY), anyString())).thenReturn("MYCOOKIE");
        when(props.getBoolean(eq(PerfHttpSessionManager.COOKIE_SECURE_KEY), eq(false))).thenReturn(true);
        when(webContext.getContextPath()).thenReturn("/api");

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);

        assertEquals("MYCOOKIE", newManager.getCookieName());
        assertEquals("/api", newManager.getCookiePath());
        assertTrue(newManager.isCookieSecure());
    }

    @Test
    void cookieConfig_sameSite_canonicalizedToNettyEnumName() {
        when(props.get(eq(PerfHttpSessionManager.COOKIE_SAME_SITE_KEY), anyString())).thenReturn("lax");
        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);
        // Netty CookieHeaderNames.SameSite 枚举常量为 Lax/Strict/None（非全大写），
        // valueOf 必须使用精确常量名，否则 SameSite 配置触发 IllegalArgumentException
        assertEquals("Lax", newManager.getSameSite());
    }

    @Test
    void cookieConfig_unknownSameSite_isIgnored() {
        // 未知值走 canonicalSameSite 的 default 分支：忽略（不猜），而不是抛异常打断启动
        when(props.get(eq(PerfHttpSessionManager.COOKIE_SAME_SITE_KEY), anyString())).thenReturn("weird");
        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);
        assertNull(newManager.getSameSite());
    }

    @Test
    void cookieConfig_domainMaxAgeHttpOnlyDefaults() {
        assertNull(manager.getCookieDomain(), "未配置 domain 时不应凭空生成");
        assertEquals(-1, manager.getCookieMaxAge(), "默认无 Max-Age（会话级 cookie）");
        assertTrue(manager.isCookieHttpOnly(), "HttpOnly 必须默认开启");
    }

    @Test
    void cookieConfig_domainMaxAgeAndHttpOnlyCustomized() {
        when(props.get(eq(PerfHttpSessionManager.COOKIE_DOMAIN_KEY), isNull())).thenReturn(".example.com");
        when(props.get(eq(PerfHttpSessionManager.COOKIE_MAX_AGE_KEY), isNull())).thenReturn("3600");
        when(props.getBoolean(eq(PerfHttpSessionManager.COOKIE_HTTP_ONLY_KEY), eq(true))).thenReturn(false);

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);

        assertEquals(".example.com", newManager.getCookieDomain());
        assertEquals(3600, newManager.getCookieMaxAge());
        assertFalse(newManager.isCookieHttpOnly());
    }

    @Test
    void cookieConfig_invalidMaxAge_fallsBackToSessionCookie() {
        // 写错的 max-age 不能让启动失败，也不能把非法值当 0（0 会立刻删除 cookie）
        when(props.get(eq(PerfHttpSessionManager.COOKIE_MAX_AGE_KEY), isNull())).thenReturn("not-a-number");

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);

        assertEquals(-1, newManager.getCookieMaxAge());
    }

    @Test
    void destroyComponent_shutsDownStorage() {
        HttpSessionStorage storage = mock(HttpSessionStorage.class);
        lenient().when(webContext.getBeanFromCtx(HttpSessionStorage.class)).thenReturn(storage);

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);
        assertDoesNotThrow(() -> newManager.destroyComponent());
        verify(storage).shutdown();
    }

    @Test
    void changeSessionId_removesOldSessionFromStorage() {
        PerfHttpSession old = manager.createSession();
        old.setAttribute("k", "v");
        PerfHttpSession fresh = manager.changeSessionId(old);
        assertNotNull(fresh);
        assertNull(manager.getSession(old.getId()));
        assertEquals("v", fresh.getAttribute("k"));
    }

    @Test
    void changeSessionId_notifiesSessionIdListenersWithOldId() {
        // Servlet 规范：ID 轮换必须通知 HttpSessionIdListener，且事件里是持有「新 ID」的会话 + 旧 ID。
        // 防会话固定攻击的场景依赖这条通知去迁移审计/安全上下文。
        java.util.concurrent.atomic.AtomicReference<HttpSessionEvent> captured = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> capturedOldId = new java.util.concurrent.atomic.AtomicReference<>();
        HttpSessionIdListener idListener = (event, oldSessionId) -> {
            captured.set(event);
            capturedOldId.set(oldSessionId);
        };
        manager.addSessionIdListener(idListener);

        PerfHttpSession old = manager.createSession();
        String oldId = old.getId();
        PerfHttpSession fresh = manager.changeSessionId(old);

        assertNotNull(captured.get(), "changeSessionId 必须触发 sessionIdChanged");
        assertEquals(oldId, capturedOldId.get(), "事件里的旧 ID 应为轮换前的 ID");
        assertSame(fresh, captured.get().getSession(), "事件应携带持有新 ID 的会话");
        assertEquals(fresh.getId(), captured.get().getSession().getId());
    }

    @Test
    void changeSessionId_newSessionIsNotNewAndKeepsTimeout() {
        PerfHttpSession old = manager.createSession();
        old.setMaxInactiveInterval(600);

        PerfHttpSession fresh = manager.changeSessionId(old);

        assertFalse(fresh.isNew(), "ID 轮换是续用既有会话，不应标记为新建");
        assertEquals(600, fresh.getMaxInactiveInterval(), "轮换不应丢失会话超时配置");
    }

    @Test
    void saveSession_invalidSession_doesNotRevive() {
        // L8：已失效会话（invalidate 已从 storage 移除并设置 data.invalid）被另一在途 wrapper
        // 调 saveSession 时，不应重新持久化复活。
        PerfHttpSession a = manager.createSession();
        String id = a.getId();
        a.invalidate();
        // 模拟同一底层 data 的另一并发 wrapper（servletContext 无关紧要，复用 manager 已持有的即可）
        PerfHttpSession b = new PerfHttpSession(a.getData(), manager.getServletContext());
        manager.saveSession(b);
        assertNull(manager.getSession(id), "已失效会话不应被 saveSession 重新持久化");
    }

    // ==================== server.servlet.session.persistent ====================

    @Test
    void persistent_enabled_usesFileStorage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws Exception {
        when(props.getBoolean(io.springperf.web.context.PropertiesConstant.SERVLET_SESSION_PERSISTENT,
                io.springperf.web.context.PropertiesConstant.SERVLET_SESSION_PERSISTENT_DEFAULT)).thenReturn(true);
        when(props.get(io.springperf.web.context.PropertiesConstant.SERVLET_SESSION_STORE_DIR,
                io.springperf.web.context.PropertiesConstant.SERVLET_SESSION_STORE_DIR_DEFAULT))
                        .thenReturn(tempDir.toString());

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);
        try {
            assertInstanceOf(FileHttpSessionStorage.class, newManager.getStorage(),
                    "persistent=true 应使用 FileHttpSessionStorage");
        } finally {
            newManager.destroyComponent();
        }
    }

    @Test
    void persistent_disabled_usesInMemoryStorage() throws Exception {
        when(props.getBoolean(io.springperf.web.context.PropertiesConstant.SERVLET_SESSION_PERSISTENT,
                io.springperf.web.context.PropertiesConstant.SERVLET_SESSION_PERSISTENT_DEFAULT)).thenReturn(false);

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);
        try {
            assertInstanceOf(InMemoryHttpSessionStorage.class, newManager.getStorage(),
                    "persistent=false 应使用 InMemoryHttpSessionStorage");
        } finally {
            newManager.destroyComponent();
        }
    }

    @Test
    void customStorageBean_takesPrecedenceOverPersistent() {
        // 容器中存在自定义 HttpSessionStorage bean 时，即使 persistent=true 也优先使用该 bean
        HttpSessionStorage custom = mock(HttpSessionStorage.class);
        when(webContext.getBeanFromCtx(HttpSessionStorage.class)).thenReturn(custom);
        lenient()
                .when(props.getBoolean(io.springperf.web.context.PropertiesConstant.SERVLET_SESSION_PERSISTENT,
                        io.springperf.web.context.PropertiesConstant.SERVLET_SESSION_PERSISTENT_DEFAULT))
                .thenReturn(true);

        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);
        assertSame(custom, newManager.getStorage());
    }
}
