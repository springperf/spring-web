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

class PerfSupportAutoConfigurationTest {

    private final PerfSupportAutoConfiguration config = new PerfSupportAutoConfiguration();

    @Test
    void configuration_hasConditionalOnClass() {
        ConditionalOnClass annotation = PerfSupportAutoConfiguration.class.getAnnotation(ConditionalOnClass.class);
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
}