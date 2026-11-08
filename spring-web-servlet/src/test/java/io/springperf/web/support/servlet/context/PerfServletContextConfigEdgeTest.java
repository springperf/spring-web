package io.springperf.web.support.servlet.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.SessionTrackingMode;

/** 启动期配置、资源查找回退、监听器容错与 bean servlet 注册的行级缺口。 */
class PerfServletContextConfigEdgeTest {

    @TempDir
    Path tempDir;

    private final List<PerfServletContext> opened = new ArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(PerfServletContext::destroyComponent);
    }

    private PerfServletContext newContext() {
        return newContext(mock(WebContext.class), mock(ApplicationProperties.class));
    }

    private PerfServletContext newContext(WebContext webContext, ApplicationProperties props) {
        doReturn(props).when(webContext).getProps();
        PerfServletContext context = new PerfServletContext(webContext);
        opened.add(context);
        return context;
    }

    private static ClassLoader streamLoader(int hitIndex) {
        AtomicInteger calls = new AtomicInteger();
        return new ClassLoader() {
            @Override
            public InputStream getResourceAsStream(String name) {
                return calls.getAndIncrement() == hitIndex ? new ByteArrayInputStream(new byte[] { 42 }) : null;
            }
        };
    }

    @Test
    void readConfig_sessionModes_skipsBlankUnknownAndSsl() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        doReturn("COOKIE, ,BOGUS, SSL , URL").when(props).get(eq(PropertiesConstant.SERVLET_SESSION_TRACKING_MODES),
                isNull());

        PerfServletContext context = newContext(mock(WebContext.class), props);

        Set<SessionTrackingMode> modes = context.getEffectiveSessionTrackingModes();
        assertEquals(2, modes.size());
        assertTrue(modes.contains(SessionTrackingMode.COOKIE));
        assertTrue(modes.contains(SessionTrackingMode.URL));
        assertFalse(modes.contains(SessionTrackingMode.SSL), "SSL 不支持，只能被忽略");
    }

    @Test
    void readConfig_allUnknownModesKeepsDefault() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        doReturn("BOGUS, ").when(props).get(eq(PropertiesConstant.SERVLET_SESSION_TRACKING_MODES), isNull());

        PerfServletContext context = newContext(mock(WebContext.class), props);

        assertEquals(Set.of(SessionTrackingMode.COOKIE), context.getEffectiveSessionTrackingModes());
    }

    @Test
    void readExplicitContextParameters_nullKeysReturnsEarly() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.getPropertyNames(PropertiesConstant.SERVLET_CONTEXT_PARAMETERS_PREFIX)).thenReturn(null);

        PerfServletContext context = newContext(mock(WebContext.class), props);

        assertNull(context.getInitParameter("anything"));
    }

    @Test
    void readExplicitContextParameters_skipsEmptyNameAndNullValue() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        String prefix = PropertiesConstant.SERVLET_CONTEXT_PARAMETERS_PREFIX;
        when(props.getPropertyNames(prefix)).thenReturn(List.of(prefix, prefix + "foo", prefix + "nil"));
        doReturn("bar").when(props).get(prefix + "foo", null);

        PerfServletContext context = newContext(mock(WebContext.class), props);

        assertEquals("bar", context.getInitParameter("foo"));
        assertNull(context.getInitParameter("nil"));
        assertNull(context.getInitParameter(""));
    }

    @Test
    void initComponentPhase1_collectsListenersFromSpringContext() {
        WebContext webContext = mock(WebContext.class);
        ApplicationContext appCtx = mock(ApplicationContext.class);
        ServletContextListener listener = mock(ServletContextListener.class);
        doReturn(appCtx).when(webContext).getCtx();
        when(appCtx.getBeansOfType(ServletContextListener.class)).thenReturn(Map.of("listener", listener));

        PerfServletContext context = newContext(webContext, mock(ApplicationProperties.class));
        context.initComponentPhase1();

        verify(listener).contextInitialized(any(ServletContextEvent.class));
    }

    @Test
    void destroyComponent_listenerFailureDoesNotAbortCleanup() {
        PerfServletContext context = newContext();
        context.addListener(new ServletContextListener() {
            @Override
            public void contextDestroyed(ServletContextEvent event) {
                throw new IllegalStateException("boom");
            }
        });
        File contextTempDir = (File) context.getAttribute(ServletContext.TEMPDIR);

        context.destroyComponent();

        assertFalse(contextTempDir.exists(), "单个监听器抛异常不得中断临时目录清理");
    }

    @Test
    void constructor_tempDirCreationFailureFallsBackToSystemTmp() throws Exception {
        Path blocker = Files.write(tempDir.resolve("blocker"), new byte[] { 1 });
        String original = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", blocker.toString());
        try {
            PerfServletContext context = newContext();

            assertEquals(blocker.toFile(), context.getAttribute(ServletContext.TEMPDIR));
            context.destroyComponent();
            assertTrue(Files.exists(blocker), "回退模式不得删除共享的系统临时目录");
        } finally {
            if (original == null) {
                System.clearProperty("java.io.tmpdir");
            } else {
                System.setProperty("java.io.tmpdir", original);
            }
        }
    }

    @Test
    void deleteRecursively_plainFileIsRemoved() throws Exception {
        Path file = Files.write(tempDir.resolve("lone.bin"), new byte[] { 1 });
        Method delete = PerfServletContext.class.getDeclaredMethod("deleteRecursively", File.class);
        delete.setAccessible(true);

        delete.invoke(null, file.toFile());

        assertFalse(Files.exists(file), "listFiles() 返回 null 时仍应删除文件本身");
    }

    @Test
    void getRealPath_handlesFileAndNonFileProtocols() throws Exception {
        PerfServletContext context = spy(newContext());
        doReturn(URI.create("file:/tmp/sample").toURL()).when(context).getResource("/sample");
        assertEquals("/tmp/sample", context.getRealPath("/sample"));

        doReturn(URI.create("https://example.invalid/x").toURL()).when(context).getResource("/remote");
        assertNull(context.getRealPath("/remote"));

        doReturn(null).when(context).getResource("/missing");
        assertNull(context.getRealPath("/missing"));
        assertNull(context.getRealPath(null));
    }

    @Test
    void getResource_nullContextClassLoaderFallsBack() throws Exception {
        PerfServletContext context = newContext();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(null);
        try {
            assertNotNull(context.getResource("/io/springperf/web/support/servlet/context/PerfServletContext.class"),
                    "TCCL 为 null 时应回退到框架自身 classloader");
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void getResourceAsStream_nullContextClassLoaderFallsBack() throws Exception {
        PerfServletContext context = newContext();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(null);
        try {
            try (InputStream in = context
                    .getResourceAsStream("/io/springperf/web/support/servlet/context/PerfServletContext.class")) {
                assertNotNull(in);
                assertTrue(in.read() >= 0, "回退后应能读到真实的 class 字节");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void getResourceAsStream_resolvesMetaInfStaticAndPublicPrefixes() throws Exception {
        PerfServletContext context = newContext();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            for (int hit = 0; hit < 3; hit++) {
                Thread.currentThread().setContextClassLoader(streamLoader(hit));
                String path = hit == 2 ? "sample.bin" : "/sample.bin";
                try (InputStream in = context.getResourceAsStream(path)) {
                    assertNotNull(in, "第 " + hit + " 个查找前缀命中时应返回流");
                    assertEquals(42, in.read());
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void registerBeanServlet_appliesInitParamsAndRejectsMappingConflict() {
        PerfServletContext context = newContext();

        context.registerBeanServlet("alpha", new PerfServletContextValidationTest.SampleServlet(),
                new String[] { "/same" }, Map.of("k", "v"));

        assertEquals("v", context.getServletRegistration("alpha").getInitParameter("k"));
        assertEquals(1, context.claimServletRegistrations().size());
        assertTrue(context.claimServletRegistrations().isEmpty(), "同一注册项只能认领一次");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> context.registerBeanServlet("beta",
                new PerfServletContextValidationTest.SampleServlet(), new String[] { "/same" }, Map.of()));
        assertTrue(ex.getMessage().contains("already taken"));
    }

    @Test
    void setSessionTrackingModes_sslOnlyIsAccepted() {
        PerfServletContext context = newContext();

        context.setSessionTrackingModes(EnumSet.of(SessionTrackingMode.SSL));

        assertEquals(EnumSet.of(SessionTrackingMode.SSL), context.getEffectiveSessionTrackingModes());
        assertThrows(IllegalArgumentException.class,
                () -> context.setSessionTrackingModes(EnumSet.of(SessionTrackingMode.URL)));
    }
}
