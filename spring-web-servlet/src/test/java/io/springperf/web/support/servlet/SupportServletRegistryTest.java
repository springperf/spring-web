package io.springperf.web.support.servlet;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.support.servlet.context.PerfServletContext;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class SupportServletRegistryTest {

    @Mock
    WebContext webContext;

    @Mock
    MappingRegistry mappingRegistry;

    @Mock
    PerfServletContext servletContext;

    @Mock
    ApplicationContext applicationContext;

    @WebServlet(name = "demo", urlPatterns = {"/demo", "/demo/*", "*.txt"})
    static class AnnotatedServlet extends HttpServlet {
    }

    static class TrackingServlet extends HttpServlet {
        int initCalls;
        int destroyCalls;

        @Override
        public void init(ServletConfig config) {
            initCalls++;
        }

        @Override
        public void destroy() {
            destroyCalls++;
        }
    }

    @WebServlet(urlPatterns = "/track")
    static class AnnotatedTrackingServlet extends HttpServlet {
        int initCalls;
        int destroyCalls;

        @Override
        public void init(ServletConfig config) {
            initCalls++;
        }

        @Override
        public void destroy() {
            destroyCalls++;
        }
    }

    private void mockEnvironment(Map<String, Servlet> beans) {
        doReturn(mappingRegistry).when(webContext).getWebComponent(MappingRegistry.class);
        doReturn(servletContext).when(webContext).getWebComponent(PerfServletContext.class);
        doReturn(applicationContext).when(webContext).getCtx();
        doReturn(beans).when(applicationContext).getBeansOfType(Servlet.class);
    }

    @Test
    void toPathRule_convertsServletPatterns() {
        assertThat(SupportServletRegistry.toPathRule("/")).isEqualTo("/**");
        assertThat(SupportServletRegistry.toPathRule(null)).isEqualTo("/**");
        assertThat(SupportServletRegistry.toPathRule("")).isEqualTo("/**");
        assertThat(SupportServletRegistry.toPathRule("/foo/*")).isEqualTo("/foo/**");
        assertThat(SupportServletRegistry.toPathRule("*.txt")).isEqualTo("/**/*.txt");
        assertThat(SupportServletRegistry.toPathRule("/demo")).isEqualTo("/demo");
    }

    @Test
    void initComponentPhase1_registersRoutesFromWebServletAnnotation() throws Exception {
        Map<String, Servlet> beans = new LinkedHashMap<>();
        beans.put("demo", new AnnotatedServlet());
        mockEnvironment(beans);

        SupportServletRegistry registry = new SupportServletRegistry();
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();

        ArgumentCaptor<PathMappingContext> captor = ArgumentCaptor.forClass(PathMappingContext.class);
        verify(mappingRegistry, times(3)).registerMapping(captor.capture());
        List<String> pathRules = captor.getAllValues().stream()
                .map(PathMappingContext::getPathRule)
                .collect(java.util.stream.Collectors.toList());
        assertThat(pathRules).containsExactlyInAnyOrder("/demo", "/demo/**", "/**/*.txt");
    }

    @Test
    void initComponentPhase1_invokesServletInit() throws Exception {
        AnnotatedTrackingServlet servlet = new AnnotatedTrackingServlet();
        Map<String, Servlet> beans = new LinkedHashMap<>();
        beans.put("tracking", servlet);
        mockEnvironment(beans);

        SupportServletRegistry registry = new SupportServletRegistry();
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();

        assertThat(servlet.initCalls).isEqualTo(1);
    }

    @Test
    void initComponentPhase1_noServlets_registersNothing() throws Exception {
        mockEnvironment(new LinkedHashMap<>());

        SupportServletRegistry registry = new SupportServletRegistry();
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();

        verify(mappingRegistry, times(0)).registerMapping(any(PathMappingContext.class));
    }

    @Test
    void initComponentPhase1_unannotatedServlet_skippedWithoutCatchAllMapping() throws Exception {
        // 无 @WebServlet 注解的 Servlet bean：应跳过注册，避免 /** 全路径通配遮蔽控制器
        Map<String, Servlet> beans = new LinkedHashMap<>();
        beans.put("plain", new TrackingServlet());
        mockEnvironment(beans);

        SupportServletRegistry registry = new SupportServletRegistry();
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();

        verify(mappingRegistry, times(0)).registerMapping(any(PathMappingContext.class));
    }

    @Test
    void resolveUrlPatterns_unannotated_returnsNull() {
        assertThat(SupportServletRegistry.resolveUrlPatterns(null)).isNull();
    }

    @Test
    void destroyComponent_callsDestroyOnInitializedServlets() throws Exception {
        AnnotatedTrackingServlet servlet = new AnnotatedTrackingServlet();
        Map<String, Servlet> beans = new LinkedHashMap<>();
        beans.put("tracking", servlet);
        mockEnvironment(beans);

        SupportServletRegistry registry = new SupportServletRegistry();
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.destroyComponent();

        assertThat(servlet.destroyCalls).isEqualTo(1);
    }
}
