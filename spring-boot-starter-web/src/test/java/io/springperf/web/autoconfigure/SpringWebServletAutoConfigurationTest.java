package io.springperf.web.autoconfigure;

import io.springperf.web.support.SupportDispatcherHandler;
import io.springperf.web.support.arg.provider.HttpServletRequestProvider;
import io.springperf.web.support.arg.provider.HttpServletResponseProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SpringWebServletAutoConfigurationTest {

    private final SpringWebServletAutoConfiguration config = new SpringWebServletAutoConfiguration();

    @Test
    void configuration_hasConditionalOnClass() {
        ConditionalOnClass annotation = SpringWebServletAutoConfiguration.class.getAnnotation(ConditionalOnClass.class);
        assertNotNull(annotation);
        assertTrue(annotation.name().length > 0);
        assertEquals("io.springperf.web.support.servlet.context.ServletAdapterContext", annotation.name()[0]);
    }

    @Test
    void supportDispatcherHandler_createsBean() {
        SupportDispatcherHandler bean = config.supportDispatcherHandler();
        assertNotNull(bean);
        assertInstanceOf(SupportDispatcherHandler.class, bean);
    }

    @Test
    void supportDispatcherHandler_returnsNewInstanceEachCall() {
        assertNotSame(config.supportDispatcherHandler(), config.supportDispatcherHandler());
    }

    @Test
    void httpServletRequestProvider_createsBean() {
        HttpServletRequestProvider bean = config.httpServletRequestProvider();
        assertNotNull(bean);
        assertInstanceOf(HttpServletRequestProvider.class, bean);
    }

    @Test
    void httpServletResponseProvider_createsBean() {
        HttpServletResponseProvider bean = config.httpServletResponseProvider();
        assertNotNull(bean);
        assertInstanceOf(HttpServletResponseProvider.class, bean);
    }

    @Test
    void servletRequestProvider_createsBean() {
        assertInstanceOf(io.springperf.web.support.arg.provider.ServletRequestProvider.class,
                config.servletRequestProvider());
    }

    @Test
    void servletResponseProvider_createsBean() {
        assertInstanceOf(io.springperf.web.support.arg.provider.ServletResponseProvider.class,
                config.servletResponseProvider());
    }

    @Test
    void webRequestArgumentResolverProvider_createsBean() {
        assertInstanceOf(io.springperf.web.support.arg.provider.WebRequestArgumentResolverProvider.class,
                config.webRequestArgumentResolverProvider());
    }

    @Test
    void sessionAttributeArgumentResolverProvider_createsBean() {
        assertInstanceOf(io.springperf.web.support.arg.provider.SessionAttributeArgumentResolverProvider.class,
                config.sessionAttributeArgumentResolverProvider());
    }

    @Test
    void sessionAttributesInterceptor_createsBean() {
        assertInstanceOf(io.springperf.web.support.model.SessionAttributesInterceptor.class,
                config.sessionAttributesInterceptor());
    }

    @Test
    void sessionStatusArgumentResolverProvider_createsBean() {
        assertInstanceOf(io.springperf.web.support.arg.provider.SessionStatusArgumentResolverProvider.class,
                config.sessionStatusArgumentResolverProvider());
    }

    @Test
    void sessionScopeBeanFactoryPostProcessor_createsBean() {
        assertInstanceOf(io.springperf.web.support.context.SessionScopeBeanFactoryPostProcessor.class,
                SpringWebServletAutoConfiguration.sessionScopeBeanFactoryPostProcessor());
    }

    @Test
    void perfServletContext_createsAndRegisters() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        io.springperf.web.context.ApplicationProperties props =
                mock(io.springperf.web.context.ApplicationProperties.class);
        org.mockito.Mockito.when(webContext.getProps()).thenReturn(props);
        io.springperf.web.support.servlet.context.PerfServletContext ctx = config.perfServletContext(webContext);
        assertNotNull(ctx);
        org.mockito.Mockito.verify(webContext).registerWebComponent(ctx);
    }

    @Test
    void perfHttpSessionManager_createsAndRegisters() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        io.springperf.web.support.servlet.session.PerfHttpSessionManager manager =
                config.perfHttpSessionManager(webContext);
        assertNotNull(manager);
        org.mockito.Mockito.verify(webContext).registerWebComponent(manager);
    }

    @Test
    void supportServletRegistry_createsAndRegisters() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        io.springperf.web.support.servlet.SupportServletRegistry registry = config.supportServletRegistry(webContext);
        assertNotNull(registry);
        org.mockito.Mockito.verify(webContext).registerWebComponent(registry);
    }
}
