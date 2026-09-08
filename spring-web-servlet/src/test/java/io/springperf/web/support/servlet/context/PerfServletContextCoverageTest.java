package io.springperf.web.support.servlet.context;

import io.springperf.web.context.WebContext;
import javax.servlet.ServletContext;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 琛ュ厖 PerfServletContext 瑕嗙洊鐜囷細灞炴€у瓨鍙栥€乮nit 鍙傛暟銆佽祫婧愯В鏋愩€佷笂涓嬫枃淇℃伅銆?
 * session cookie config銆佺紪鐮佷笌鏃跺尯銆佹敞鍐岀被鏂规硶绌哄疄鐜扮瓑銆?
 */
@ExtendWith(MockitoExtension.class)
class PerfServletContextCoverageTest {

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
        servletContext.destroyComponent();
    }

    /* ==================== Attributes ==================== */

    @Test
    void attributes_setGetRemove() {
        servletContext.setAttribute("k1", "v1");
        assertEquals("v1", servletContext.getAttribute("k1"));

        servletContext.setAttribute("k2", null);
        assertNull(servletContext.getAttribute("k2"));

        servletContext.setAttribute("k3", "v3");
        servletContext.removeAttribute("k3");
        assertNull(servletContext.getAttribute("k3"));
    }

    @Test
    void attributeNames_containsSetAttributes() {
        servletContext.setAttribute("a", 1);
        servletContext.setAttribute("b", 2);
        Enumeration<String> names = servletContext.getAttributeNames();
        assertTrue(names.hasMoreElements());
    }

    /* ==================== Init Parameters ==================== */

    @Test
    void initParameter_setGetNames() {
        assertTrue(servletContext.setInitParameter("p1", "v1"));
        assertFalse(servletContext.setInitParameter("p1", "v2"), "閲嶅璁剧疆搴旇繑鍥?false");
        assertEquals("v1", servletContext.getInitParameter("p1"));
        assertNotNull(servletContext.getInitParameterNames());
    }

    /* ==================== 涓婁笅鏂囦俊鎭?==================== */

    @Test
    void versions_andNames() {
        assertEquals(6, servletContext.getMajorVersion());
        assertEquals(0, servletContext.getMinorVersion());
        assertEquals(6, servletContext.getEffectiveMajorVersion());
        assertEquals(0, servletContext.getEffectiveMinorVersion());
        assertEquals("spring-perf-web", servletContext.getServletContextName());
        assertEquals("spring-perf-web", servletContext.getServerInfo());
        assertEquals("localhost", servletContext.getVirtualServerName());
        assertNull(servletContext.getContext("/other"));
    }

    /* ==================== 璧勬簮瑙ｆ瀽 ==================== */

    @Test
    void getResource_nullOrEmpty_returnsNull() {
        assertNull(servletContext.getResource(null));
        assertNull(servletContext.getResource(""));
    }

    @Test
    void getResource_knownClasspathResource_returnsUrl() {
        URL url = servletContext.getResource("/perf-web/placeholder.txt");
        assertNotNull(url, "妗嗘灦绫昏矾寰勪笅搴旇兘鎵惧埌璧勬簮");
    }

    @Test
    void getResourceAsStream_knownResource_returnsStream() throws Exception {
        try (InputStream in = servletContext.getResourceAsStream("/perf-web/placeholder.txt")) {
            assertNotNull(in);
        }
    }

    @Test
    void getResourceAsStream_nullOrEmpty_returnsNull() {
        assertNull(servletContext.getResourceAsStream(null));
        assertNull(servletContext.getResourceAsStream(""));
    }

    @Test
    void getResourceAsStream_unknown_returnsNull() {
        assertNull(servletContext.getResourceAsStream("/not/exist.txt"));
    }

    @Test
    void getRealPath_null_returnsNull() {
        assertNull(servletContext.getRealPath(null));
    }

    /* ==================== RequestDispatcher / Logging ==================== */

    @Test
    void dispatchers_nullAndNonNull() {
        assertNull(servletContext.getRequestDispatcher(null));
        assertNotNull(servletContext.getRequestDispatcher("/x"));
        assertNull(servletContext.getNamedDispatcher(null));
        assertNotNull(servletContext.getNamedDispatcher("x"));
    }

    @Test
    void logging_doesNotThrow() {
        assertDoesNotThrow(() -> servletContext.log("msg"));
        assertDoesNotThrow(() -> servletContext.log("msg", new RuntimeException("x")));
    }

    /* ==================== Session ==================== */

    @Test
    void sessionTrackingModes() {
        Set<SessionTrackingMode> def = servletContext.getDefaultSessionTrackingModes();
        assertEquals(1, def.size());
        assertTrue(def.contains(SessionTrackingMode.COOKIE));
        assertNotNull(servletContext.getEffectiveSessionTrackingModes());
        assertDoesNotThrow(() -> servletContext.setSessionTrackingModes(Collections.singleton(SessionTrackingMode.URL)));
    }

    @Test
    void sessionTimeout_defaultAndSettable() {
        assertTrue(servletContext.getSessionTimeout() > 0);
        servletContext.setSessionTimeout(120);
        assertEquals(120, servletContext.getSessionTimeout());
    }

    @Test
    void sessionCookieConfig_settersAndGetters() {
        SessionCookieConfig cfg = servletContext.getSessionCookieConfig();
        assertNotNull(cfg);
        cfg.setName("SID");
        assertEquals("SID", cfg.getName());
        cfg.setDomain("example.com");
        assertEquals("example.com", cfg.getDomain());
        cfg.setPath("/");
        assertEquals("/", cfg.getPath());
        cfg.setComment("c");
        assertEquals("c", cfg.getComment());
        cfg.setHttpOnly(false);
        assertFalse(cfg.isHttpOnly());
        cfg.setSecure(true);
        assertTrue(cfg.isSecure());
        cfg.setMaxAge(100);
        assertEquals(100, cfg.getMaxAge());
    }

    /* ==================== 缂栫爜 ==================== */

    @Test
    void characterEncodings_defaultAndSettable() {
        assertEquals("UTF-8", servletContext.getRequestCharacterEncoding());
        assertEquals("UTF-8", servletContext.getResponseCharacterEncoding());
        servletContext.setRequestCharacterEncoding("GBK");
        servletContext.setResponseCharacterEncoding("GBK");
        assertEquals("GBK", servletContext.getRequestCharacterEncoding());
        assertEquals("GBK", servletContext.getResponseCharacterEncoding());
    }

    /* ==================== 娉ㄥ唽绫荤┖瀹炵幇 ==================== */

    @Test
    void registrationMethods_noopOrNull() {
        assertNull(servletContext.addServlet("n", "class"));
        assertNull(servletContext.addServlet("n", mock(javax.servlet.Servlet.class)));
        assertNull(servletContext.addServlet("n", javax.servlet.Servlet.class));
        assertNull(servletContext.addJspFile("n", "x.jsp"));
        assertNull(servletContext.createServlet(javax.servlet.Servlet.class));
        assertNull(servletContext.getServletRegistration("n"));
        assertTrue(servletContext.getServletRegistrations().isEmpty());

        assertNull(servletContext.addFilter("n", "class"));
        assertNull(servletContext.addFilter("n", mock(javax.servlet.Filter.class)));
        assertNull(servletContext.addFilter("n", javax.servlet.Filter.class));
        assertNull(servletContext.createFilter(javax.servlet.Filter.class));
        assertNull(servletContext.getFilterRegistration("n"));
        assertTrue(servletContext.getFilterRegistrations().isEmpty());

        assertDoesNotThrow(() -> servletContext.addListener("x"));
        assertNull(servletContext.createListener(javax.servlet.ServletContextListener.class));
        assertDoesNotThrow(() -> servletContext.addListener(javax.servlet.ServletContextListener.class));
        assertDoesNotThrow(() -> servletContext.addListener(mock(javax.servlet.ServletContextListener.class)));
        assertDoesNotThrow(() -> servletContext.declareRoles("role"));
        assertNull(servletContext.getJspConfigDescriptor());
    }
}