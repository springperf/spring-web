package io.springperf.web.support.view;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.support.servlet.ServletInvoker;
import io.springperf.web.support.servlet.SpringInstanceManager;
import io.springperf.web.support.servlet.context.PerfServletContext;
import io.springperf.web.view.View;
import jakarta.servlet.ServletContext;
import jakarta.servlet.jsp.JspFactory;
import org.apache.jasper.Options;
import org.apache.jasper.compiler.TldCache;
import org.apache.jasper.runtime.JspFactoryImpl;
import org.apache.jasper.servlet.JspServlet;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.SimpleInstanceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JspViewResolverTest {

    @Mock
    WebContext webContext;
    @Mock
    MappingRegistry mappingRegistry;
    @Mock
    WebServerHttpRequest req;

    private String viewPath(View view) {
        return (String) ReflectionTestUtils.getField(view, "path");
    }

    // ==================== 视图名解析 ====================

    @Test
    void defaultConstructor_usesJspPrefixAndSuffix() {
        JspViewResolver resolver = new JspViewResolver();
        View view = resolver.resolveViewName("jsp:index", Locale.US, req);
        assertNotNull(view);
        assertInstanceOf(JspView.class, view);
        assertEquals("/jsp/index.jsp", viewPath(view));
    }

    @Test
    void customPrefixAndSuffix() {
        JspViewResolver resolver = new JspViewResolver("/templates/", ".jspx");
        assertEquals("/templates/page.jspx", viewPath(resolver.resolveViewName("jsp:page", Locale.US, req)));
    }

    @Test
    void resolveViewName_null_returnsNull() {
        assertNull(new JspViewResolver().resolveViewName(null, Locale.US, req));
    }

    @Test
    void resolveViewName_nonJspName_returnsNull() {
        assertNull(new JspViewResolver().resolveViewName("hello", Locale.US, req));
    }

    @Test
    void resolveViewName_suffixForm_mapsToPrefix() {
        JspViewResolver resolver = new JspViewResolver();
        View view = resolver.resolveViewName("hello.jsp", Locale.US, req);
        assertNotNull(view);
        assertEquals("/jsp/hello.jsp", viewPath(view));
    }

    // ==================== server.servlet.jsp.* → Jasper init-param ====================

    /** 全部取默认值的配置：三个类型化键回退 false，无显式 init-parameter 块。 */
    private static ApplicationProperties propsWithDefaults() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(props.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(props.getPropertyNames(anyString())).thenReturn(Collections.emptyList());
        return props;
    }

    private JspViewResolver preparePhase1(ApplicationProperties props) {
        // JspFactory 是全局静态；每个用例从干净状态开始，避免上一个用例的 JspServlet 初始化结果串味
        JspFactory.setDefaultFactory(null);
        when(webContext.getProps()).thenReturn(props);
        PerfServletContext servletContext = new PerfServletContext(webContext);
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);
        when(webContext.getWebComponent(PerfServletContext.class)).thenReturn(servletContext);
        JspViewResolver resolver = new JspViewResolver();
        resolver.initWithWebContext(webContext);
        return resolver;
    }

    private JspServlet capturedJspServlet() {
        ArgumentCaptor<PathMappingContext> captor = ArgumentCaptor.forClass(PathMappingContext.class);
        verify(mappingRegistry).registerMapping(captor.capture());
        ServletInvoker invoker = (ServletInvoker) captor.getValue().getInvoker();
        return (JspServlet) invoker.getServlet();
    }

    @Test
    void buildJspInitParameters_defaultsToProductionMode() {
        Map<String, String> params = JspViewResolver.buildJspInitParameters(propsWithDefaults());

        assertEquals("false", params.get(JspViewResolver.JASPER_PARAM_DEVELOPMENT),
                "development 默认 false：Jasper 自身默认 true（每次请求检查 JSP 修改并重编译）");
        assertEquals("false", params.get(JspViewResolver.JASPER_PARAM_KEEP_GENERATED));
        assertEquals("false", params.get(JspViewResolver.JASPER_PARAM_TRIM_SPACES));
        assertEquals(3, params.size());
    }

    @Test
    void buildJspInitParameters_mapsTypeKeysToJasperParameterNames() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.getBoolean(PropertiesConstant.SERVLET_JSP_DEVELOPMENT, false)).thenReturn(true);
        when(props.getBoolean(PropertiesConstant.SERVLET_JSP_KEEP_GENERATED, false)).thenReturn(true);
        when(props.getBoolean(PropertiesConstant.SERVLET_JSP_TRIM_SPACES, false)).thenReturn(true);
        when(props.getPropertyNames(anyString())).thenReturn(Collections.emptyList());

        Map<String, String> params = JspViewResolver.buildJspInitParameters(props);

        assertEquals("true", params.get("development"));
        assertEquals("true", params.get("keepgenerated"));
        assertEquals("true", params.get("trimSpaces"), "Jasper 的参数名是大写 S 的 trimSpaces，写错即静默失效");
    }

    @Test
    void buildJspInitParameters_explicitBlockOverridesTypeKeysAndPassesThrough() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        String prefix = PropertiesConstant.SERVLET_JSP_INIT_PARAMETERS_PREFIX;
        when(props.getPropertyNames(prefix))
                .thenReturn(List.of(prefix + "development", prefix + "fork", prefix + "empty"));
        when(props.get(prefix + "development", null)).thenReturn("true");
        when(props.get(prefix + "fork", null)).thenReturn("true");
        when(props.get(prefix + "empty", null)).thenReturn(null);

        Map<String, String> params = JspViewResolver.buildJspInitParameters(props);

        assertEquals("true", params.get("development"), "init-parameters 显式块优先级最高（类型化键为 false）");
        assertEquals("true", params.get("fork"), "Jasper 的其余参数应原样透传");
        assertFalse(params.containsKey("empty"), "值为空的键不传递，避免把 null 变成字符串");
    }

    @Test
    void buildJspInitParameters_noInitParameterBlock_returnsTypeKeysOnly() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        // getPropertyNames 未桩化 → 返回 null（脱离 ConfigurableEnvironment 的真实语义），不得 NPE
        Map<String, String> params = JspViewResolver.buildJspInitParameters(props);
        assertEquals(3, params.size());
    }

    @Test
    void buildJspInitParameters_nullProps_returnsEmpty() {
        assertTrue(JspViewResolver.buildJspInitParameters(null).isEmpty());
    }

    // ==================== Phase 1：Jasper 装配 ====================

    @Test
    void initComponentPhase1_registersJspServletRoute() throws Exception {
        JspViewResolver resolver = preparePhase1(propsWithDefaults());
        resolver.initComponentPhase1();

        verify(mappingRegistry).registerMapping(argThat(ctx -> ctx.getPathRule().equals("/**/*.jsp")));
        assertNotNull(JspFactory.getDefaultFactory(), "初始化后应设置 JspFactory");
        assertInstanceOf(JspFactoryImpl.class, JspFactory.getDefaultFactory());
        ServletContext servletContext = capturedJspServlet().getServletContext();
        assertNotNull(TldCache.getInstance(servletContext), "初始化后应设置 TldCache");
    }

    @Test
    void initComponentPhase1_developmentDefaultsFalse_reachesJasper() throws Exception {
        JspViewResolver resolver = preparePhase1(propsWithDefaults());
        resolver.initComponentPhase1();

        // 不能只断言我们构造的 Map：必须确认 Jasper 的 Options 真的读到了 false
        Options options = (Options) ReflectionTestUtils.getField(capturedJspServlet(), "options");
        assertNotNull(options, "JspServlet.init 应创建 EmbeddedServletOptions");
        assertFalse(options.getDevelopment(), "生产默认 development=false 必须真正生效");
    }

    @Test
    void initComponentPhase1_springContextAvailable_installsSpringInstanceManager() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getAutowireCapableBeanFactory())
                .thenReturn(mock(org.springframework.beans.factory.config.AutowireCapableBeanFactory.class));
        when(webContext.getCtx()).thenReturn(ctx);

        JspViewResolver resolver = preparePhase1(propsWithDefaults());
        resolver.initComponentPhase1();

        ServletContext servletContext = capturedJspServlet().getServletContext();
        assertInstanceOf(SpringInstanceManager.class, servletContext.getAttribute(InstanceManager.class.getName()),
                "有 Spring 上下文时应让 JSP 生成类获得依赖注入");
    }

    @Test
    void initComponentPhase1_existingInstanceManager_isPreserved() throws Exception {
        // 容器或用户已设置 InstanceManager（如 Tomcat 自带实现）时不得被覆盖
        ApplicationProperties props = propsWithDefaults();
        when(webContext.getProps()).thenReturn(props);
        PerfServletContext servletContext = new PerfServletContext(webContext);
        SimpleInstanceManager preset = new SimpleInstanceManager();
        servletContext.setAttribute(InstanceManager.class.getName(), preset);
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);
        when(webContext.getWebComponent(PerfServletContext.class)).thenReturn(servletContext);
        JspFactory.setDefaultFactory(null);

        JspViewResolver resolver = new JspViewResolver();
        resolver.initWithWebContext(webContext);
        resolver.initComponentPhase1();

        assertSame(preset, servletContext.getAttribute(InstanceManager.class.getName()));
        assertSame(preset, capturedJspServlet().getServletContext().getAttribute(InstanceManager.class.getName()));
    }

    @Test
    void initComponentPhase1_withoutMappingRegistry_skipsGracefully() {
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(null);
        JspViewResolver resolver = new JspViewResolver();
        resolver.initWithWebContext(webContext);
        assertDoesNotThrow(() -> resolver.initComponentPhase1());
    }
}
