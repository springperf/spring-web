package io.springperf.web.support.servlet.context;

import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfServletContextTest {

    @Mock WebContext webContext;

    private PerfServletContext servletContext;
    private io.springperf.web.context.ApplicationProperties props;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getContextPath()).thenReturn("/app");
        props = mock(io.springperf.web.context.ApplicationProperties.class);
        lenient().when(props.get(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(webContext.getProps()).thenReturn(props);
        servletContext = new PerfServletContext(webContext);
    }

    @AfterEach
    void tearDown() {
        // 构造 PerfServletContext 会在系统临时目录创建 spring-perf-web-<nanoTime>，
        // 除验证清理语义的用例（自行调用 destroy）外，统一清理避免残留目录
        servletContext.destroyComponent();
    }

    @Test
    void getContextPath_returnsWebContextPath() {
        assertEquals("/app", servletContext.getContextPath());
    }

    @Test
    void getMimeType_known() {
        assertEquals("text/html", servletContext.getMimeType("test.html"));
        assertEquals("text/css", servletContext.getMimeType("style.css"));
        assertEquals("application/javascript", servletContext.getMimeType("app.js"));
        assertEquals("application/json", servletContext.getMimeType("data.json"));
        assertEquals("image/png", servletContext.getMimeType("image.png"));
        assertEquals("image/jpeg", servletContext.getMimeType("photo.jpg"));
        assertEquals("image/svg+xml", servletContext.getMimeType("icon.svg"));
        assertEquals("font/woff2", servletContext.getMimeType("font.woff2"));
        assertEquals("application/pdf", servletContext.getMimeType("doc.pdf"));
    }

    @Test
    void getMimeType_unknown_returnsNull() {
        assertNull(servletContext.getMimeType("test.unknown"));
    }

    @Test
    void getMimeType_noExtension_returnsNull() {
        assertNull(servletContext.getMimeType("test"));
    }

    @Test
    void getMimeType_null_returnsNull() {
        assertNull(servletContext.getMimeType(null));
    }

    @Test
    void getMimeType_caseInsensitive() {
        assertEquals("text/html", servletContext.getMimeType("test.HTML"));
        assertEquals("image/jpeg", servletContext.getMimeType("photo.JPG"));
    }

    @Test
    void getServerInfo_returnsSpringPerfWeb() {
        assertEquals("spring-perf-web", servletContext.getServerInfo());
    }

    @Test
    void getMajorVersion_returns6() {
        assertEquals(6, servletContext.getMajorVersion());
    }

    @Test
    void getSessionCookieConfig_notNull() {
        assertNotNull(servletContext.getSessionCookieConfig());
    }

    @Test
    void getInitParameter_returnsNull() {
        assertNull(servletContext.getInitParameter("nonexistent"));
    }

    @Test
    void getResourcePaths_returnsEmptySet() {
        assertNotNull(servletContext.getResourcePaths("/"));
        assertTrue(servletContext.getResourcePaths("/").isEmpty());
    }

    @Test
    void getClassLoader_returnsContextClassLoader() {
        assertNotNull(servletContext.getClassLoader());
    }

    @Test
    void getRequestDispatcher_returnsDispatcher() {
        assertNotNull(servletContext.getRequestDispatcher("/test"));
    }

    @Test
    void getNamedDispatcher_returnsDispatcher() {
        assertNotNull(servletContext.getNamedDispatcher("testServlet"));
    }

    @Test
    void getVirtualServerName_returnsDefault() {
        assertEquals("localhost", servletContext.getVirtualServerName());
    }

    @Test
    void getRealPath_returnsNullForNonFileResource() {
        assertNull(servletContext.getRealPath("/nonexistent"));
    }

    @Test
    void tempDir_createdAndAttributeSet() {
        Object tempDir = servletContext.getAttribute(javax.servlet.ServletContext.TEMPDIR);
        assertNotNull(tempDir);
        assertTrue(((java.io.File) tempDir).isDirectory());
    }

    @Test
    void destroyComponent_removesCreatedTempDir() {
        java.io.File tempDir = (java.io.File) servletContext.getAttribute(javax.servlet.ServletContext.TEMPDIR);
        assertTrue(tempDir.exists());

        servletContext.destroyComponent();

        assertFalse(tempDir.exists(), "鑷缓涓存椂鐩綍搴斿湪閿€姣佹椂琚竻鐞?);
    }

    @Test
    void destroyComponent_twice_isIdempotent() {
        servletContext.destroyComponent();
        assertDoesNotThrow(servletContext::destroyComponent);
    }
}