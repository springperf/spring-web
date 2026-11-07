package io.springperf.web.support.servlet.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.support.servlet.session.PerfHttpSessionManager;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRequestListener;
import javax.servlet.SessionTrackingMode;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

/** ServletContext 监听器注册/派发、资源四路回退与会话跟踪模式校验。 */
@ExtendWith(MockitoExtension.class)
class PerfServletContextListenerTest {

    @Mock
    WebContext webContext;

    private PerfServletContext servletContext;

    @BeforeEach
    void setUp() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(props.get(anyString(), nullable(String.class))).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(props.getDurationSeconds(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(webContext.getProps()).thenReturn(props);
        servletContext = new PerfServletContext(webContext);
    }

    @AfterEach
    void tearDown() {
        servletContext.destroyComponent();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<ServletContextListener> contextListeners(PerfServletContext ctx) throws Exception {
        Field field = PerfServletContext.class.getDeclaredField("contextListeners");
        field.setAccessible(true);
        return (java.util.List<ServletContextListener>) field.get(ctx);
    }

    @Test
    void addListener_nullVariantsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> servletContext.addListener((String) null));
        assertThrows(IllegalArgumentException.class,
                () -> servletContext.addListener((Class<? extends EventListener>) null));
        assertThrows(IllegalArgumentException.class, () -> servletContext.addListener((EventListener) null));
    }

    @Test
    void addListener_unknownClassNameFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.addListener("no.such.Listener"));
        assertTrue(ex.getMessage().contains("Listener class not found"));
    }

    @Test
    void addListener_nonEventListenerClassFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.addListener(String.class.getName()));
        assertTrue(ex.getMessage().contains("does not implement"));
    }

    @Test
    void addListener_byClassNameRegistersContextListener() throws Exception {
        servletContext.addListener(NoopContextListener.class.getName());

        assertEquals(1, contextListeners(servletContext).size());
        assertInstanceOf(NoopContextListener.class, contextListeners(servletContext).get(0));
    }

    @Test
    void addListener_unsupportedRequestLevelListenerFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.addListener(mock(ServletRequestListener.class)));
        assertTrue(ex.getMessage().contains("Unsupported listener type"));
    }

    @Test
    void addListener_sessionListenersDelegateToSessionManager() {
        PerfHttpSessionManager manager = mock(PerfHttpSessionManager.class);
        when(webContext.getWebComponent(PerfHttpSessionManager.class)).thenReturn(manager);
        HttpSessionListener sessionListener = mock(HttpSessionListener.class);
        HttpSessionAttributeListener attributeListener = mock(HttpSessionAttributeListener.class);
        HttpSessionIdListener idListener = mock(HttpSessionIdListener.class);

        servletContext.addListener(sessionListener);
        servletContext.addListener(attributeListener);
        servletContext.addListener(idListener);

        verify(manager).addSessionListener(sessionListener);
        verify(manager).addAttributeListener(attributeListener);
        verify(manager).addSessionIdListener(idListener);
    }

    @Test
    void addListener_sessionListenerWithoutManagerFailsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> servletContext.addListener(mock(HttpSessionListener.class)));
        assertTrue(ex.getMessage().contains("session support is not enabled"));
    }

    @Test
    void initComponentPhase1_dispatchesToAllListenersEvenWhenOneThrows() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(webContext.getCtx()).thenReturn(ctx);
        ServletContextListener failing = mock(ServletContextListener.class);
        ServletContextListener healthy = mock(ServletContextListener.class);
        doThrow(new RuntimeException("listener boom")).when(failing).contextInitialized(any());
        Map<String, ServletContextListener> beans = new java.util.LinkedHashMap<>();
        beans.put("failing", failing);
        beans.put("healthy", healthy);
        doReturn(beans).when(ctx).getBeansOfType(ServletContextListener.class);

        servletContext.initComponentPhase1();

        verify(failing).contextInitialized(any(ServletContextEvent.class));
        verify(healthy).contextInitialized(any(ServletContextEvent.class));
    }

    @Test
    void setSessionTrackingModes_validatesAndExposesUnmodifiableSet() {
        assertThrows(IllegalArgumentException.class, () -> servletContext.setSessionTrackingModes(null));
        assertThrows(IllegalArgumentException.class,
                () -> servletContext.setSessionTrackingModes(Collections.emptySet()));
        assertThrows(IllegalArgumentException.class,
                () -> servletContext.setSessionTrackingModes(EnumSet.of(SessionTrackingMode.URL)));

        Set<SessionTrackingMode> modes = EnumSet.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL);
        servletContext.setSessionTrackingModes(modes);

        assertEquals(modes, servletContext.getEffectiveSessionTrackingModes());
        assertThrows(UnsupportedOperationException.class,
                () -> servletContext.getEffectiveSessionTrackingModes().add(SessionTrackingMode.SSL));
    }

    @Test
    void getResource_resolvesEachClasspathPrefixAndHandlesMissing(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("META-INF/resources"));
        Files.createDirectories(root.resolve("static"));
        Files.createDirectories(root.resolve("public"));
        Files.write(root.resolve("META-INF/resources/p1.txt"), "1".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("static/p2.txt"), "2".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("public/p3.txt"), "3".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("p4.txt"), "4".getBytes(StandardCharsets.UTF_8));

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] { root.toUri().toURL() }, original)) {
            Thread.currentThread().setContextClassLoader(loader);

            assertTrue(servletContext.getResource("/p1.txt").toString().contains("META-INF/resources"));
            assertTrue(servletContext.getResource("p2.txt").toString().contains("static"));
            assertTrue(servletContext.getResource("p3.txt").toString().contains("public"));
            assertTrue(servletContext.getResource("/p4.txt").toString().endsWith("p4.txt"));
            assertNull(servletContext.getResource(null));
            assertNull(servletContext.getResource(""));
            assertNull(servletContext.getResource("does-not-exist.txt"));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    public static class NoopContextListener implements ServletContextListener {
        @Override
        public void contextInitialized(ServletContextEvent sce) {
        }
    }
}
