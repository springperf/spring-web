package io.springperf.web.support.servlet.context;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletSecurityElement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code ServletContext#addServlet}/{@code addFilter} 的注册语义：窗口内登记、冲突上报、能力缺口显形。
 */
class DynamicRegistrationTest {

    private PerfServletContext servletContext;

    @BeforeEach
    void setUp() {
        WebContext webContext = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(props.getPropertyNames(anyString())).thenReturn(Collections.emptyList());
        when(webContext.getProps()).thenReturn(props);
        servletContext = new PerfServletContext(webContext);
    }

    @AfterEach
    void tearDown() {
        servletContext.destroyComponent();
    }

    // ==================== addServlet ====================

    @Test
    void addServlet_registersInstanceAndExposesItInQueries() {
        Servlet servlet = mock(Servlet.class);

        ServletRegistration.Dynamic registration = servletContext.addServlet("dyn", servlet);

        assertEquals("dyn", registration.getName());
        assertSame(registration, servletContext.getServletRegistration("dyn"));
        assertSame(registration, servletContext.getServletRegistrations().get("dyn"));
        assertTrue(servletContext.getServletRegistrations().size() == 1);
    }

    @Test
    void addServlet_viaClassName_instantiatesServlet() {
        ServletRegistration.Dynamic registration = servletContext.addServlet("dyn", TrackingServlet.class.getName());

        assertInstanceOf(TrackingServlet.class, ((DynamicServletRegistration) registration).getServlet());
    }

    @Test
    void addServlet_duplicateName_throwsIllegalArgument() {
        servletContext.addServlet("dup", mock(Servlet.class));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> servletContext.addServlet("dup", mock(Servlet.class)));
        assertEquals("A servlet registration with name 'dup' already exists in this ServletContext",
                failure.getMessage());
    }

    @Test
    void addServlet_invalidNameOrClass_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> servletContext.addServlet(null, mock(Servlet.class)));
        assertThrows(IllegalArgumentException.class, () -> servletContext.addServlet("", mock(Servlet.class)));
        assertThrows(IllegalArgumentException.class, () -> servletContext.addServlet("n", "no.such.Servlet"));
        assertThrows(IllegalArgumentException.class, () -> servletContext.addServlet("n", String.class.getName()));
    }

    @Test
    void getServletRegistrations_isUnmodifiable() {
        servletContext.addServlet("dyn", mock(Servlet.class));
        Map<String, ? extends ServletRegistration> registrations = servletContext.getServletRegistrations();

        assertThrows(UnsupportedOperationException.class, () -> registrations.clear());
    }

    // ==================== ServletRegistration.Dynamic ====================

    @Test
    void addMapping_conflictingPatternIsReportedInsteadOfSilentlyOverriding() {
        ServletRegistration.Dynamic first = servletContext.addServlet("first", mock(Servlet.class));
        ServletRegistration.Dynamic second = servletContext.addServlet("second", mock(Servlet.class));

        assertTrue(first.addMapping("/shared").isEmpty());
        assertEquals(Collections.singleton("/shared"), second.addMapping("/shared"),
                "同一 url-pattern 已被占用时应返回冲突集合（规范契约），而不是覆盖已有路由");
        assertFalse(second.getMappings().contains("/shared"));
    }

    @Test
    void addMapping_sameServletTwice_isIdempotent() {
        ServletRegistration.Dynamic registration = servletContext.addServlet("dyn", mock(Servlet.class));
        registration.addMapping("/a", "/a");
        registration.addMapping("/a");

        assertEquals(1, registration.getMappings().size());
    }

    @Test
    void addMapping_invalidPattern_throwsIllegalArgument() {
        ServletRegistration.Dynamic registration = servletContext.addServlet("dyn", mock(Servlet.class));

        assertThrows(IllegalArgumentException.class, () -> registration.addMapping((String[]) null));
        assertThrows(IllegalArgumentException.class, () -> registration.addMapping());
        assertThrows(IllegalArgumentException.class, () -> registration.addMapping("  "));
        assertThrows(IllegalArgumentException.class, () -> registration.addMapping("no-slash"));
    }

    @Test
    void setInitParameter_firstWinsAndConflictsAreReported() {
        ServletRegistration.Dynamic registration = servletContext.addServlet("dyn", mock(Servlet.class));

        assertTrue(registration.setInitParameter("k", "v1"));
        assertFalse(registration.setInitParameter("k", "v2"), "重复设置应返回 false（规范）");
        assertEquals("v1", registration.getInitParameter("k"));
        assertEquals(Collections.singleton("k"),
                registration.setInitParameters(Collections.singletonMap("k", "v3")));
        assertEquals("v1", registration.getInitParameters().get("k"));
    }

    @Test
    void setServletSecurity_reportsAllMappingsAsNotApplied() {
        ServletRegistration.Dynamic registration = servletContext.addServlet("dyn", mock(Servlet.class));
        registration.addMapping("/a", "/b");

        // 框架没有请求级安全约束模型：必须返回「一条都没应用」，而不是假装接受
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("/a", "/b")),
                registration.setServletSecurity(new ServletSecurityElement()));
    }

    // ==================== addFilter ====================

    @Test
    void addFilter_recordsUrlPatternsAndInitParams() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("dynF", new TrackingFilter());
        registration.setInitParameter("k", "v");
        registration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/api/*");

        assertEquals("dynF", registration.getName());
        assertEquals("v", registration.getInitParameter("k"));
        assertTrue(registration.getUrlPatternMappings().contains("/api/*"));
        assertSame(registration, servletContext.getFilterRegistration("dynF"));
        assertTrue(registration.getServletNameMappings().isEmpty());
    }

    @Test
    void addFilter_mappingForServletNames_isUnsupported() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("dynF", new TrackingFilter());

        assertThrows(UnsupportedOperationException.class,
                () -> registration.addMappingForServletNames(null, true, "someServlet"));
    }

    @Test
    void addFilter_insertingBeforeExistingFilters_isUnsupported() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("dynF", new TrackingFilter());

        assertThrows(UnsupportedOperationException.class,
                () -> registration.addMappingForUrlPatterns(null, false, "/api/*"));
    }

    @Test
    void addFilter_nonRequestDispatcherType_isUnsupported() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("dynF", new TrackingFilter());

        assertThrows(UnsupportedOperationException.class,
                () -> registration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.ERROR), true, "/api/*"));
    }

    @Test
    void addFilter_viaClassName_instantiatesFilter() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("dynF", TrackingFilter.class.getName());

        assertInstanceOf(TrackingFilter.class, ((DynamicFilterRegistration) registration).getFilter());
    }

    @Test
    void addFilter_duplicateName_throwsIllegalArgument() {
        servletContext.addFilter("dup", new TrackingFilter());

        assertThrows(IllegalArgumentException.class, () -> servletContext.addFilter("dup", new TrackingFilter()));
    }

    // ==================== 窗口 ====================

    @Test
    void afterInitialization_allWriteOperationsAreRejectedWithIllegalState() {
        ServletRegistration.Dynamic servletRegistration = servletContext.addServlet("dyn", mock(Servlet.class));
        FilterRegistration.Dynamic filterRegistration = servletContext.addFilter("dynF", new TrackingFilter());

        servletContext.initComponentPhase2();

        assertThrows(IllegalStateException.class, () -> servletContext.addServlet("n", mock(Servlet.class)));
        assertThrows(IllegalStateException.class, () -> servletContext.addFilter("n", new TrackingFilter()));
        // 已登记的注册项同样必须拒绝：Phase1/Phase3 之后这些修改永远不会被认领，静默接受等于骗调用方
        assertThrows(IllegalStateException.class, () -> servletRegistration.addMapping("/late"));
        assertThrows(IllegalStateException.class, () -> servletRegistration.setInitParameter("k", "v"));
        assertThrows(IllegalStateException.class, () -> servletRegistration.setAsyncSupported(true));
        assertThrows(IllegalStateException.class,
                () -> servletRegistration.setServletSecurity(new ServletSecurityElement()));
        assertThrows(IllegalStateException.class,
                () -> filterRegistration.addMappingForUrlPatterns(null, true, "/late/*"));
        assertThrows(IllegalStateException.class, () -> filterRegistration.setInitParameter("k", "v"));
    }

    @Test
    void addJspFile_isUnsupportedRegardlessOfWindow() {
        assertThrows(UnsupportedOperationException.class, () -> servletContext.addJspFile("n", "/x.jsp"));
    }

    /** 公开无参构造器，供 addServlet(String, String) / createServlet 反射实例化。 */
    public static class TrackingServlet extends javax.servlet.http.HttpServlet {
    }

    /** 公开无参构造器，供 addFilter(String, String) 反射实例化。 */
    public static class TrackingFilter implements Filter {
        boolean initialized;
        String initParam;

        @Override
        public void init(javax.servlet.FilterConfig filterConfig) {
            initialized = true;
            initParam = filterConfig.getInitParameter("k");
        }

        @Override
        public void doFilter(javax.servlet.ServletRequest request, javax.servlet.ServletResponse response,
                javax.servlet.FilterChain chain) {
        }

        @Override
        public void destroy() {
        }
    }

    // ==================== 剩余参数校验与 no-op setter ====================

    @Test
    void validatePattern_acceptsAllSpecFormsAndRejectsNull() {
        assertEquals("/", DynamicServletRegistration.validatePattern(" / "));
        assertEquals("/api/*", DynamicServletRegistration.validatePattern(" /api/* "));
        assertEquals("*.json", DynamicServletRegistration.validatePattern("*.json"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DynamicServletRegistration.validatePattern(null));
        assertTrue(ex.getMessage().contains("must not be null"));
    }

    @Test
    void servletRegistration_exposesRunAsRoleAndClassName() {
        ServletRegistration.Dynamic registration = servletContext.addServlet("meta", new TrackingServlet());

        assertNull(registration.getRunAsRole());
        assertEquals(TrackingServlet.class.getName(), registration.getClassName());
    }

    @Test
    void servletSetInitParameter_rejectsInvalidNameAndNullValue() {
        ServletRegistration.Dynamic registration = servletContext.addServlet("params", mock(Servlet.class));

        assertThrows(IllegalArgumentException.class, () -> registration.setInitParameter(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> registration.setInitParameter("", "v"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registration.setInitParameter("k", null));
        assertEquals("Init parameter value must not be null: k", ex.getMessage());
    }

    @Test
    void servletSetInitParameters_rejectsNullMap() {
        ServletRegistration.Dynamic registration = servletContext.addServlet("params", mock(Servlet.class));

        assertThrows(IllegalArgumentException.class, () -> registration.setInitParameters(null));
    }

    @Test
    void servletNoOpSetters_keepMappingsIntactAndRecordRunAsRole() {
        ServletRegistration.Dynamic registration = servletContext.addServlet("noop", mock(Servlet.class));
        registration.addMapping("/keep");

        registration.setLoadOnStartup(1);
        registration.setMultipartConfig(new MultipartConfigElement("/tmp"));
        registration.setRunAsRole("admin");
        registration.setAsyncSupported(true);

        assertEquals("admin", registration.getRunAsRole(), "setRunAsRole 是唯一有状态的 setter");
        assertEquals(java.util.Collections.singleton("/keep"), new java.util.LinkedHashSet<>(registration.getMappings()));
    }

    @Test
    void filterAddMapping_rejectsNullOrEmptyUrlPatterns() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("f", new TrackingFilter());

        assertThrows(IllegalArgumentException.class,
                () -> registration.addMappingForUrlPatterns(null, true));
        assertThrows(IllegalArgumentException.class,
                () -> registration.addMappingForUrlPatterns(null, true, (String[]) null));
    }

    @Test
    void filterAddMapping_acceptsNullEmptyAndRequestOnlyDispatcherTypes() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("f", new TrackingFilter());

        registration.addMappingForUrlPatterns(null, true, "/a");
        registration.addMappingForUrlPatterns(EnumSet.noneOf(DispatcherType.class), true, "/b");
        registration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/c");

        assertEquals(new java.util.LinkedHashSet<>(java.util.Arrays.asList("/a", "/b", "/c")), new java.util.LinkedHashSet<>(registration.getUrlPatternMappings()));
    }

    @Test
    void filterRegistration_exposesClassName() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("f", new TrackingFilter());
        assertEquals(TrackingFilter.class.getName(), registration.getClassName());
    }

    @Test
    void filterSetInitParameter_rejectsInvalidNameAndNullValue() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("f", new TrackingFilter());

        assertThrows(IllegalArgumentException.class, () -> registration.setInitParameter(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> registration.setInitParameter("", "v"));
        assertThrows(IllegalArgumentException.class, () -> registration.setInitParameter("k", null));
    }

    @Test
    void filterSetInitParameter_duplicateReturnsFalse() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("f", new TrackingFilter());

        assertTrue(registration.setInitParameter("k", "v1"));
        assertFalse(registration.setInitParameter("k", "v2"));
        assertEquals("v1", registration.getInitParameter("k"));
    }

    @Test
    void filterSetInitParameters_appliesMapAndReportsConflicts() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("f", new TrackingFilter());
        registration.setInitParameter("taken", "v1");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("fresh", "v2");
        params.put("taken", "v3");

        assertEquals(java.util.Collections.singleton("taken"), registration.setInitParameters(params));
        assertEquals("v2", registration.getInitParameter("fresh"));
        assertEquals("v1", registration.getInitParameter("taken"));
        assertTrue(registration.setInitParameters(Collections.emptyMap()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registration.setInitParameters(null));
    }

    @Test
    void filterSetAsyncSupported_doesNotAlterConfiguration() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("f", new TrackingFilter());
        registration.setInitParameter("k", "v");
        registration.addMappingForUrlPatterns(null, true, "/a");

        registration.setAsyncSupported(true);

        assertEquals("v", registration.getInitParameter("k"));
        assertEquals(java.util.Collections.singleton("/a"), new java.util.LinkedHashSet<>(registration.getUrlPatternMappings()));
    }

    @Test
    void filterAddMapping_multipleDispatcherTypesIsUnsupported() {
        FilterRegistration.Dynamic registration = servletContext.addFilter("f", new TrackingFilter());

        assertThrows(UnsupportedOperationException.class,
                () -> registration.addMappingForUrlPatterns(
                        EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD), true, "/d"));
    }
}
