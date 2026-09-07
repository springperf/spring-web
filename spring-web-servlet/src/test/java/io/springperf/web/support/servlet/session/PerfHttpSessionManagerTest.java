package io.springperf.web.support.servlet.session;

import io.springperf.web.context.WebContext;
import io.springperf.web.support.servlet.Authenticator;
import io.springperf.web.support.servlet.context.PerfServletContext;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfHttpSessionManagerTest {

    @Mock WebContext webContext;
    @Mock io.springperf.web.context.ApplicationProperties props;

    private PerfHttpSessionManager manager;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(webContext.getCtx()).thenReturn(mock(org.springframework.context.ApplicationContext.class));
        lenient().when(props.get(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
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
    void cookieConfig_sameSite_uppercased() {
        when(props.get(eq(PerfHttpSessionManager.COOKIE_SAME_SITE_KEY), anyString())).thenReturn("lax");
        PerfHttpSessionManager newManager = new PerfHttpSessionManager();
        newManager.initWithWebContext(webContext);
        assertEquals("LAX", newManager.getSameSite());
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
}
