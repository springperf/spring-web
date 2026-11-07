package io.springperf.web.support.servlet.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;

/** 动态注册 API 的参数校验、实例化/autowire 与临时目录清理分支。 */
class PerfServletContextValidationTest {

    private WebContext webContext;
    private PerfServletContext servletContext;

    @BeforeEach
    void setUp() {
        webContext = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.getPropertyNames(anyString())).thenReturn(Collections.emptyList());
        doReturn(props).when(webContext).getProps();
        servletContext = new PerfServletContext(webContext);
    }

    @AfterEach
    void tearDown() {
        servletContext.destroyComponent();
    }

    @Test
    void addServlet_rejectsNullClassName() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.addServlet("s", (String) null));
        assertTrue(ex.getMessage().contains("Servlet class name must not be null"));
    }

    @Test
    void addServlet_rejectsNullServlet() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.addServlet("s", (Servlet) null));
        assertTrue(ex.getMessage().contains("Servlet must not be null"));
    }

    @Test
    void addServlet_rejectsNullServletClass() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.addServlet("s", (Class<? extends Servlet>) null));
        assertTrue(ex.getMessage().contains("Servlet class must not be null"));
    }

    @Test
    void getServletRegistration_nullNameReturnsNull() {
        assertNull(servletContext.getServletRegistration(null));
    }

    @Test
    void addFilter_rejectsNullClassName() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.addFilter("f", (String) null));
        assertTrue(ex.getMessage().contains("Filter class name must not be null"));
    }

    @Test
    void addFilter_unknownClassReportsCause() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.addFilter("f", "no.such.Filter"));

        assertTrue(ex.getMessage().contains("Filter class not found"));
        assertInstanceOf(ClassNotFoundException.class, ex.getCause());
    }

    @Test
    void addFilter_rejectsNonFilterClass() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.addFilter("f", String.class.getName()));
        assertTrue(ex.getMessage().contains("does not implement jakarta.servlet.Filter"));
    }

    @Test
    void addFilter_rejectsNullFilter() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.addFilter("f", (Filter) null));
        assertTrue(ex.getMessage().contains("Filter must not be null"));
    }

    @Test
    void addFilter_rejectsNullFilterClass() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.addFilter("f", (Class<? extends Filter>) null));
        assertTrue(ex.getMessage().contains("Filter class must not be null"));
    }

    @Test
    void getFilterRegistration_nullNameReturnsNull() {
        assertNull(servletContext.getFilterRegistration(null));
    }

    @Test
    void createFilter_instantiatesAndClaimsOnce() {
        Filter filter = servletContext.createFilter(SampleFilter.class);
        assertNotNull(filter);
        assertNotNull(servletContext.addFilter("f", filter));

        assertEquals(1, servletContext.claimFilterRegistrations().size());
        assertTrue(servletContext.claimFilterRegistrations().isEmpty(), "同一注册项只能认领一次");
    }

    @Test
    void addServletAndFilterByClass_instantiatesComponents() {
        assertNotNull(servletContext.addServlet("s", SampleServlet.class));
        assertNotNull(servletContext.addFilter("f", SampleFilter.class));
        assertNotNull(servletContext.getServletRegistration("s"));
        assertNotNull(servletContext.getFilterRegistration("f"));
    }

    @Test
    void createServlet_rejectsNullType() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.createServlet(null));
        assertTrue(ex.getMessage().contains("servlet class must not be null"));
    }

    @Test
    void createListener_rejectsNullClassAndInstantiates() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> servletContext.createListener(null));
        assertTrue(ex.getMessage().contains("Listener class must not be null"));
        assertNotNull(servletContext.createListener(SampleListener.class));
    }

    @Test
    void instantiate_autowiresThroughSpringContext() {
        ApplicationContext appCtx = mock(ApplicationContext.class);
        AutowireCapableBeanFactory factory = mock(AutowireCapableBeanFactory.class);
        when(appCtx.getAutowireCapableBeanFactory()).thenReturn(factory);
        doReturn(appCtx).when(webContext).getCtx();

        Servlet servlet = servletContext.createServlet(SampleServlet.class);

        assertNotNull(servlet);
        verify(factory).autowireBean(servlet);
    }

    @Test
    void instantiate_autowireFailureFailsFast() {
        ApplicationContext appCtx = mock(ApplicationContext.class);
        when(appCtx.getAutowireCapableBeanFactory()).thenReturn(null);
        doReturn(appCtx).when(webContext).getCtx();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> servletContext.createServlet(SampleServlet.class));
        assertTrue(ex.getMessage().contains("Failed to autowire servlet"));
    }

    @Test
    void destroyComponent_removesNestedTempContent() throws Exception {
        File tempDir = (File) servletContext.getAttribute(ServletContext.TEMPDIR);
        assertNotNull(tempDir);
        assertTrue(tempDir.getName().startsWith("spring-perf-web-"),
                "前置条件：只清理本实例创建的目录，避免误删共享临时目录");
        File nested = new File(tempDir, "nested");
        assertTrue(nested.mkdirs());
        Files.write(new File(nested, "child").toPath(), new byte[] { 1 });

        servletContext.destroyComponent();

        assertFalse(tempDir.exists(), "应用级临时目录应连同子目录/文件一并清理");
    }

    public static class SampleServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
    }

    public static class SampleFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        }
    }

    public static class SampleListener implements ServletContextListener {
    }
}
