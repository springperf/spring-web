package io.springperf.web.support.servlet.context;

import io.springperf.web.context.WebContext;
import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 补充 PerfServletContext 覆盖率：属性存取、init 参数、资源解析、上下文信息、
 * session cookie config、编码与时区、注册类方法空实现等。
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
        // 兜底：未显式桩化时 getDurationSeconds 返回传入默认值（模拟“未配置”），
        // 避免 Mockito 默认 0 导致 session timeout 为 0。
        lenient().when(props.getDurationSeconds(anyString(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));
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
        assertFalse(servletContext.setInitParameter("p1", "v2"), "重复设置应返回 false");
        assertEquals("v1", servletContext.getInitParameter("p1"));
        assertNotNull(servletContext.getInitParameterNames());
    }

    // ==================== server.servlet.context-parameters.* 显式块 ====================

    @Test
    void contextParameters_explicitBlock_exposedAsInitParameters() {
        // 构造新的 mock：显式块键 "server.servlet.context-parameters.foo" → init param "foo"
        io.springperf.web.context.WebContext wc = mock(io.springperf.web.context.WebContext.class);
        io.springperf.web.context.ApplicationProperties p =
                mock(io.springperf.web.context.ApplicationProperties.class);
        lenient().when(wc.getContextPath()).thenReturn("/app");
        lenient().when(wc.getProps()).thenReturn(p);
        lenient().when(p.getDurationSeconds(anyString(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(p.getPropertyNames(
                        io.springperf.web.context.PropertiesConstant.SERVLET_CONTEXT_PARAMETERS_PREFIX))
                .thenReturn(java.util.List.of(
                        "server.servlet.context-parameters.foo",
                        "server.servlet.context-parameters.bar"));
        lenient().when(p.get("server.servlet.context-parameters.foo", null)).thenReturn("FOO");
        lenient().when(p.get("server.servlet.context-parameters.bar", null)).thenReturn("BAR");

        PerfServletContext ctx = new PerfServletContext(wc);
        try {
            assertEquals("FOO", ctx.getInitParameter("foo"));
            assertEquals("BAR", ctx.getInitParameter("bar"));
            // init parameter names 应包含显式块键
            java.util.Set<String> names = new java.util.HashSet<>();
            ctx.getInitParameterNames().asIterator().forEachRemaining(names::add);
            assertTrue(names.contains("foo"));
            assertTrue(names.contains("bar"));
        } finally {
            ctx.destroyComponent();
        }
    }

    @Test
    void contextParameters_noBlock_doesNotThrow() {
        // setUp 的 props.getPropertyNames 未桩化 → 返回 null，应被防御处理
        assertNull(servletContext.getInitParameter("absent"));
    }

    // ==================== server.servlet.virtual-server-name ====================

    @Test
    void virtualServerName_configured_usesNewKey() {
        io.springperf.web.context.WebContext wc = mock(io.springperf.web.context.WebContext.class);
        io.springperf.web.context.ApplicationProperties p =
                mock(io.springperf.web.context.ApplicationProperties.class);
        lenient().when(wc.getContextPath()).thenReturn("/app");
        lenient().when(wc.getProps()).thenReturn(p);
        lenient().when(p.getDurationSeconds(anyString(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(p.get(io.springperf.web.context.PropertiesConstant.SERVLET_VIRTUAL_SERVER_NAME,
                        io.springperf.web.context.PropertiesConstant.SERVLET_VIRTUAL_SERVER_NAME_DEFAULT))
                .thenReturn("perf-host");

        PerfServletContext ctx = new PerfServletContext(wc);
        try {
            assertEquals("perf-host", ctx.getVirtualServerName());
        } finally {
            ctx.destroyComponent();
        }
    }

    @Test
    void virtualServerName_defaultIsLocalhost() {
        assertEquals("localhost", servletContext.getVirtualServerName());
    }

    // ==================== server.servlet.encoding.force-request / force-response ====================

    @Test
    void encoding_forceRequest_ignoresBusinessOverride() {
        PerfServletContext ctx = newContextWithEncoding(true, false);
        try {
            assertEquals("UTF-8", ctx.getRequestCharacterEncoding());
            ctx.setRequestCharacterEncoding("GBK");
            assertEquals("UTF-8", ctx.getRequestCharacterEncoding(), "force-request=true 应忽略业务设置");
            // 响应未强制：可被覆盖
            ctx.setResponseCharacterEncoding("GBK");
            assertEquals("GBK", ctx.getResponseCharacterEncoding());
        } finally {
            ctx.destroyComponent();
        }
    }

    @Test
    void encoding_forceResponse_ignoresBusinessOverride() {
        PerfServletContext ctx = newContextWithEncoding(false, true);
        try {
            assertEquals("UTF-8", ctx.getResponseCharacterEncoding());
            ctx.setResponseCharacterEncoding("GBK");
            assertEquals("UTF-8", ctx.getResponseCharacterEncoding(), "force-response=true 应忽略业务设置");
            // 请求未强制：可被覆盖
            ctx.setRequestCharacterEncoding("GBK");
            assertEquals("GBK", ctx.getRequestCharacterEncoding());
        } finally {
            ctx.destroyComponent();
        }
    }

    @Test
    void encoding_noForce_allowsOverride() {
        PerfServletContext ctx = newContextWithEncoding(false, false);
        try {
            ctx.setRequestCharacterEncoding("GBK");
            ctx.setResponseCharacterEncoding("GBK");
            assertEquals("GBK", ctx.getRequestCharacterEncoding());
            assertEquals("GBK", ctx.getResponseCharacterEncoding());
        } finally {
            ctx.destroyComponent();
        }
    }

    /** 构造一个 force-request/force-response 按参数配置的 ServletContext。 */
    private PerfServletContext newContextWithEncoding(boolean forceRequest, boolean forceResponse) {
        io.springperf.web.context.WebContext wc = mock(io.springperf.web.context.WebContext.class);
        io.springperf.web.context.ApplicationProperties p =
                mock(io.springperf.web.context.ApplicationProperties.class);
        lenient().when(wc.getContextPath()).thenReturn("/app");
        lenient().when(wc.getProps()).thenReturn(p);
        lenient().when(p.getDurationSeconds(anyString(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(p.get(io.springperf.web.context.PropertiesConstant.SERVLET_ENCODING_CHARSET,
                        io.springperf.web.context.PropertiesConstant.SERVLET_ENCODING_CHARSET_DEFAULT))
                .thenReturn("UTF-8");
        lenient().when(p.getBoolean(io.springperf.web.context.PropertiesConstant.SERVLET_ENCODING_FORCE, false))
                .thenReturn(false);
        lenient().when(p.getBoolean(io.springperf.web.context.PropertiesConstant.SERVLET_ENCODING_FORCE_REQUEST, false))
                .thenReturn(forceRequest);
        lenient().when(p.getBoolean(io.springperf.web.context.PropertiesConstant.SERVLET_ENCODING_FORCE_RESPONSE, false))
                .thenReturn(forceResponse);
        return new PerfServletContext(wc);
    }

    /* ==================== 上下文信息 ==================== */

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

    /* ==================== 资源解析 ==================== */

    @Test
    void getResource_nullOrEmpty_returnsNull() {
        assertNull(servletContext.getResource(null));
        assertNull(servletContext.getResource(""));
    }

    @Test
    void getResource_knownClasspathResource_returnsUrl() {
        URL url = servletContext.getResource("/perf-web/placeholder.txt");
        assertNotNull(url, "框架类路径下应能找到资源");
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
        assertDoesNotThrow(() -> servletContext.setSessionTrackingModes(Set.of(SessionTrackingMode.URL)));
    }

    @Test
    void sessionTimeout_defaultAndSettable() {
        assertTrue(servletContext.getSessionTimeout() > 0);
        servletContext.setSessionTimeout(120);
        assertEquals(120, servletContext.getSessionTimeout());
    }

    @Test
    void sessionTimeout_durationMinutes_parsedToMinutes() {
        when(props.getDurationSeconds("server.servlet.session.timeout", 1800)).thenReturn(1800L);
        PerfServletContext ctx = new PerfServletContext(webContext);
        try {
            assertEquals(30, ctx.getSessionTimeout(), "30m = 1800s → 30 分钟");
        } finally {
            ctx.destroyComponent();
        }
    }

    @Test
    void sessionTimeout_hoursSuffix_parsedToMinutes() {
        when(props.getDurationSeconds("server.servlet.session.timeout", 1800)).thenReturn(3600L);
        PerfServletContext ctx = new PerfServletContext(webContext);
        try {
            assertEquals(60, ctx.getSessionTimeout(), "1h = 3600s → 60 分钟");
        } finally {
            ctx.destroyComponent();
        }
    }

    @Test
    void sessionTimeout_bareSeconds_parsedToMinutes() {
        when(props.getDurationSeconds("server.servlet.session.timeout", 1800)).thenReturn(90L);
        PerfServletContext ctx = new PerfServletContext(webContext);
        try {
            assertEquals(1, ctx.getSessionTimeout(), "90s → 1 分钟（整数截断）");
        } finally {
            ctx.destroyComponent();
        }
    }

    @Test
    void sessionTimeout_invalidValue_fallsBackToDefault() {
        when(props.getDurationSeconds("server.servlet.session.timeout", 1800))
                .thenThrow(new IllegalArgumentException("Unknown duration unit: c"));
        PerfServletContext ctx = new PerfServletContext(webContext);
        try {
            assertEquals(30, ctx.getSessionTimeout(), "非法值兜底 1800s → 30 分钟");
        } finally {
            ctx.destroyComponent();
        }
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
        cfg.setAttribute("a", "1");
        assertEquals("1", cfg.getAttribute("a"));
        cfg.setAttribute("a", null);
        assertNull(cfg.getAttribute("a"));
        assertNotNull(cfg.getAttributes());
    }

    /* ==================== 编码 ==================== */

    @Test
    void characterEncodings_defaultAndSettable() {
        assertEquals("UTF-8", servletContext.getRequestCharacterEncoding());
        assertEquals("UTF-8", servletContext.getResponseCharacterEncoding());
        servletContext.setRequestCharacterEncoding("GBK");
        servletContext.setResponseCharacterEncoding("GBK");
        assertEquals("GBK", servletContext.getRequestCharacterEncoding());
        assertEquals("GBK", servletContext.getResponseCharacterEncoding());
    }

    /* ==================== 注册类空实现 ==================== */

    @Test
    void registrationMethods_noopOrNull() {
        assertNull(servletContext.addServlet("n", "class"));
        assertNull(servletContext.addServlet("n", mock(jakarta.servlet.Servlet.class)));
        assertNull(servletContext.addServlet("n", jakarta.servlet.Servlet.class));
        assertNull(servletContext.addJspFile("n", "x.jsp"));
        assertNull(servletContext.createServlet(jakarta.servlet.Servlet.class));
        assertNull(servletContext.getServletRegistration("n"));
        assertTrue(servletContext.getServletRegistrations().isEmpty());

        assertNull(servletContext.addFilter("n", "class"));
        assertNull(servletContext.addFilter("n", mock(jakarta.servlet.Filter.class)));
        assertNull(servletContext.addFilter("n", jakarta.servlet.Filter.class));
        assertNull(servletContext.createFilter(jakarta.servlet.Filter.class));
        assertNull(servletContext.getFilterRegistration("n"));
        assertTrue(servletContext.getFilterRegistrations().isEmpty());

        assertDoesNotThrow(() -> servletContext.addListener("x"));
        assertNull(servletContext.createListener(jakarta.servlet.ServletContextListener.class));
        assertDoesNotThrow(() -> servletContext.addListener(jakarta.servlet.ServletContextListener.class));
        assertDoesNotThrow(() -> servletContext.addListener(mock(jakarta.servlet.ServletContextListener.class)));
        assertDoesNotThrow(() -> servletContext.declareRoles("role"));
        assertNull(servletContext.getJspConfigDescriptor());
    }
}