package io.springperf.web.autoconfigure;

import io.springperf.web.support.SupportDispatcherHandler;
import io.springperf.web.support.arg.provider.HttpServletRequestProvider;
import io.springperf.web.support.arg.provider.HttpServletResponseProvider;
import io.springperf.web.support.async.stream.ResponseBodyEmitterReturnValueResolver;
import io.springperf.web.support.codec.interceptor.SupportHttpBodyCodecInterceptorRegistry;
import io.springperf.web.support.mvc.interceptor.SupportInterceptorRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SpringWebSupportAutoConfigurationTest {

    private final SpringWebSupportAutoConfiguration config = new SpringWebSupportAutoConfiguration();

    @Test
    void configuration_hasConditionalOnClass() {
        ConditionalOnClass annotation = SpringWebSupportAutoConfiguration.class.getAnnotation(ConditionalOnClass.class);
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
    void supportInterceptorRegistry_createsBean() {
        SupportInterceptorRegistry bean = config.supportInterceptorRegistry();
        assertNotNull(bean);
        assertInstanceOf(SupportInterceptorRegistry.class, bean);
    }

    @Test
    void supportHttpBodyCodecInterceptorRegistry_createsBean() {
        SupportHttpBodyCodecInterceptorRegistry bean = config.supportHttpBodyCodecInterceptorRegistry();
        assertNotNull(bean);
        assertInstanceOf(SupportHttpBodyCodecInterceptorRegistry.class, bean);
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
    void responseBodyEmitterReturnValueResolver_createsBean() {
        ResponseBodyEmitterReturnValueResolver bean = config.responseBodyEmitterReturnValueResolver();
        assertNotNull(bean);
        assertInstanceOf(ResponseBodyEmitterReturnValueResolver.class, bean);
    }

    @Test
    void modelAndViewReturnValueResolver_createsBean() {
        assertInstanceOf(io.springperf.web.support.mvc.retval.ModelAndViewReturnValueResolver.class,
                config.modelAndViewReturnValueResolver());
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
                SpringWebSupportAutoConfiguration.sessionScopeBeanFactoryPostProcessor());
    }

    @Test
    void webMvcConfigurerBridge_registersComponent() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        io.springperf.web.support.mvc.config.WebMvcConfigurerBridge bridge = config.webMvcConfigurerBridge(webContext);
        assertNotNull(bridge);
        org.mockito.Mockito.verify(webContext).registerWebComponent(bridge);
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
