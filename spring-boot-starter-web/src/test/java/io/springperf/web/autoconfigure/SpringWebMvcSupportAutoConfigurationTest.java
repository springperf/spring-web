package io.springperf.web.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SpringWebMvcSupportAutoConfigurationTest {

    private final SpringWebMvcSupportAutoConfiguration config = new SpringWebMvcSupportAutoConfiguration();

    @Test
    void configuration_hasConditionalOnClass() {
        ConditionalOnClass annotation = SpringWebMvcSupportAutoConfiguration.class.getAnnotation(ConditionalOnClass.class);
        assertNotNull(annotation);
        assertTrue(annotation.name().length > 0);
        assertEquals("org.springframework.web.servlet.HandlerInterceptor", annotation.name()[0]);
    }

    @Test
    void supportInterceptorRegistry_createsBean() {
        io.springperf.web.support.mvc.interceptor.SupportInterceptorRegistry bean = config.supportInterceptorRegistry();
        assertNotNull(bean);
        assertInstanceOf(io.springperf.web.support.mvc.interceptor.SupportInterceptorRegistry.class, bean);
    }

    @Test
    void supportHttpBodyCodecInterceptorRegistry_createsBean() {
        io.springperf.web.support.codec.interceptor.SupportHttpBodyCodecInterceptorRegistry bean =
                config.supportHttpBodyCodecInterceptorRegistry();
        assertNotNull(bean);
        assertInstanceOf(io.springperf.web.support.codec.interceptor.SupportHttpBodyCodecInterceptorRegistry.class, bean);
    }

    @Test
    void responseBodyEmitterReturnValueResolver_createsBean() {
        io.springperf.web.support.async.stream.ResponseBodyEmitterReturnValueResolver bean =
                config.responseBodyEmitterReturnValueResolver();
        assertNotNull(bean);
        assertInstanceOf(io.springperf.web.support.async.stream.ResponseBodyEmitterReturnValueResolver.class, bean);
    }

    @Test
    void modelAndViewReturnValueResolver_createsBean() {
        assertInstanceOf(io.springperf.web.support.mvc.retval.ModelAndViewReturnValueResolver.class,
                config.modelAndViewReturnValueResolver());
    }

    @Test
    void webMvcConfigurerBridge_registersComponent() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        io.springperf.web.support.mvc.config.WebMvcConfigurerBridge bridge = config.webMvcConfigurerBridge(webContext);
        assertNotNull(bridge);
        org.mockito.Mockito.verify(webContext).registerWebComponent(bridge);
    }
}
