package io.springperf.web.support.servlet.session;

import io.springperf.web.context.WebContext;
import io.springperf.web.support.servlet.context.PerfServletContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        lenient().when(props.get(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
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
}