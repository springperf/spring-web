package io.springperf.web.support.servlet.session;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfHttpSessionTest {

    @Mock ServletContext servletContext;

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
        session.removeAttribute("nonexistent");
    }

    @Test
    void getAttributeNames() {
        session.setAttribute("k1", "v1");
        session.setAttribute("k2", "v2");
        List<String> names = new ArrayList<>();
        java.util.Collections.list(session.getAttributeNames()).forEach(names::add);
        assertTrue(names.contains("k1"));
        assertTrue(names.contains("k2"));
        assertEquals(2, names.size());
    }

    @Test
    void invalidate_clearsAttributes() {
        session.setAttribute("key", "value");
        session.invalidate();
        assertTrue(session.isInvalid());
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
        session.setAttribute("key", "plain-value");
        session.removeAttribute("key");
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
    }

    // ===================== Listeners =====================

    @Test
    void setAttribute_firesAttributeAddedListener() {
        HttpSessionAttributeListener attrListener = mock(HttpSessionAttributeListener.class);
        List<HttpSessionAttributeListener> attrListeners = new ArrayList<>();
        attrListeners.add(attrListener);
        session = new PerfHttpSession(data, servletContext, new ArrayList<>(), attrListeners);
        session.setAttribute("key", "value");
        verify(attrListener).attributeAdded(any(HttpSessionBindingEvent.class));
    }

    @Test
    void setAttribute_firesAttributeReplacedListener() {
        HttpSessionAttributeListener attrListener = mock(HttpSessionAttributeListener.class);
        List<HttpSessionAttributeListener> attrListeners = new ArrayList<>();
        attrListeners.add(attrListener);
        session = new PerfHttpSession(data, servletContext, new ArrayList<>(), attrListeners);
        session.setAttribute("key", "old");
        session.setAttribute("key", "new");
        verify(attrListener).attributeReplaced(any(HttpSessionBindingEvent.class));
    }

    @Test
    void removeAttribute_firesAttributeRemovedListener() {
        HttpSessionAttributeListener attrListener = mock(HttpSessionAttributeListener.class);
        List<HttpSessionAttributeListener> attrListeners = new ArrayList<>();
        attrListeners.add(attrListener);
        session = new PerfHttpSession(data, servletContext, new ArrayList<>(), attrListeners);
        session.setAttribute("key", "value");
        session.removeAttribute("key");
        verify(attrListener).attributeRemoved(any(HttpSessionBindingEvent.class));
    }

    @Test
    void invalidate_firesSessionDestroyedListener() {
        HttpSessionListener sessionListener = mock(HttpSessionListener.class);
        List<HttpSessionListener> sessionListeners = new ArrayList<>();
        sessionListeners.add(sessionListener);
        session = new PerfHttpSession(data, servletContext, sessionListeners, new ArrayList<>());
        session.invalidate();
        verify(sessionListener).sessionDestroyed(any(HttpSessionEvent.class));
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
        session.markAccessed();
        assertTrue(session.getLastAccessedTime() >= 1000L);
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

    // ===================== Test helper =====================

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