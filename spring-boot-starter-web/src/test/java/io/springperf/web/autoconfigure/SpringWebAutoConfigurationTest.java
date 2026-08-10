package io.springperf.web.autoconfigure;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpringWebAutoConfigurationTest {

    private final SpringWebAutoConfiguration config = new SpringWebAutoConfiguration();

    @Test
    void dispatcherHandler_createsNewDispatcherHandler() {
        DispatcherHandler handler = config.dispatcherHandler();
        assertNotNull(handler);
        assertInstanceOf(DispatcherHandler.class, handler);
    }

    @Test
    void dispatcherHandler_returnsNewInstanceEachCall() {
        assertNotSame(config.dispatcherHandler(), config.dispatcherHandler());
    }

    @Test
    void applicationProperties_createsNewApplicationProperties() {
        ApplicationProperties props = config.applicationProperties();
        assertNotNull(props);
        assertInstanceOf(ApplicationProperties.class, props);
    }

    @Test
    void applicationProperties_returnsNewInstanceEachCall() {
        assertNotSame(config.applicationProperties(), config.applicationProperties());
    }

    @Test
    void webContext_createsBeanWhenDispatcherHandlerAvailable() {
        DispatcherHandler handler = mock(DispatcherHandler.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(anyString(), anyString())).thenReturn("/");

        assertNotNull(config.webContext(Collections.singletonList(handler), props));
    }

    @Test
    void nettyHttpServer_createsNettyHttpServer() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        Environment environment = mock(Environment.class);
        when(environment.getProperty("server.http2.enabled", boolean.class, false)).thenReturn(false);

        NettyHttpServer server = config.nettyHttpServer(webContext, environment, mock(ObjectProvider.class));
        assertNotNull(server);
        assertInstanceOf(NettyHttpServer.class, server);
    }

    /* ==================== C9: webContext/nettyHttpServer 可被用户覆盖 ==================== */

    @Test
    void webContext_annotatedWithConditionalOnMissingBean() throws Exception {
        Method m = SpringWebAutoConfiguration.class.getMethod("webContext", List.class, ApplicationProperties.class);
        assertNotNull(m.getAnnotation(ConditionalOnMissingBean.class),
                "webContext 必须 @ConditionalOnMissingBean，否则用户无法覆盖");
    }

    @Test
    void nettyHttpServer_annotatedWithConditionalOnMissingBean() throws Exception {
        Method m = SpringWebAutoConfiguration.class.getMethod("nettyHttpServer",
                WebContext.class, Environment.class, ObjectProvider.class);
        assertNotNull(m.getAnnotation(ConditionalOnMissingBean.class),
                "nettyHttpServer 必须 @ConditionalOnMissingBean，否则用户无法覆盖");
    }

    /* ==================== D9: 冲突检测提前到 BeanFactoryPostProcessor ==================== */

    @Test
    void springMvcConflictGuard_isStaticBeanFactoryPostProcessor() throws Exception {
        Method m = SpringWebAutoConfiguration.class.getMethod("springMvcConflictGuard");
        assertTrue(Modifier.isStatic(m.getModifiers()), "守卫必须为 static @Bean，容器早期即可注册");
        assertNotNull(m.getAnnotation(Bean.class));

        Object result = m.invoke(null);
        assertInstanceOf(BeanFactoryPostProcessor.class, result);
    }

    @Test
    void springMvcConflictGuard_postProcess_noSpringMvc_passes() throws Exception {
        // 测试 classpath 无 spring-webmvc：postProcessBeanFactory 必须静默通过（不抛）
        Method m = SpringWebAutoConfiguration.class.getMethod("springMvcConflictGuard");
        BeanFactoryPostProcessor guard = (BeanFactoryPostProcessor) m.invoke(null);

        assertDoesNotThrow(() -> guard.postProcessBeanFactory(mock(ConfigurableListableBeanFactory.class)));
    }
}