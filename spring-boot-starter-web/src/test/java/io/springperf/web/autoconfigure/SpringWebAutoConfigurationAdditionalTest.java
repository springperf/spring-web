package io.springperf.web.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.springperf.web.core.filter.AccessLogWebFilter;
import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpringWebAutoConfigurationAdditionalTest {

    private final SpringWebAutoConfiguration config = new SpringWebAutoConfiguration();

    @Test
    void accessLogWebFilter_withFormat_fromEnvironment() {
        Environment env = mock(Environment.class);
        when(env.getProperty("server.accesslog.format")).thenReturn("%m %U");
        AccessLogWebFilter filter = config.accessLogWebFilter(env);
        assertNotNull(filter);
        assertEquals(Integer.MIN_VALUE, filter.getOrder(), "访问日志 Filter 应最早执行");
    }

    @Test
    void accessLogWebFilter_nullFormat_usesDefault() {
        Environment env = mock(Environment.class);
        when(env.getProperty("server.accesslog.format")).thenReturn(null);
        AccessLogWebFilter filter = config.accessLogWebFilter(env);
        assertNotNull(filter);
    }

    @Test
    void validator_createsOptionalValidatorFactoryBean() {
        Validator validator = config.validator();
        assertNotNull(validator);
        assertInstanceOf(OptionalValidatorFactoryBean.class, validator);
    }

    @Test
    void micrometerWebMetrics_registersGaugesAndReturnsMetrics() throws Exception {
        SpringWebAutoConfiguration.MicrometerWebMetricsConfiguration micConfig =
                new SpringWebAutoConfiguration.MicrometerWebMetricsConfiguration();
        MeterRegistry registry = new SimpleMeterRegistry();
        NettyHttpServer server = mock(NettyHttpServer.class);

        io.springperf.web.core.metrics.WebMetrics metrics =
                micConfig.micrometerWebMetrics(registry, server);

        assertNotNull(metrics);
        assertInstanceOf(io.springperf.web.autoconfigure.metrics.MicrometerWebMetrics.class, metrics);
        // 两个 Gauge 已注册
        assertNotNull(registry.get("netty.connections.active").gauge());
        assertNotNull(registry.get("netty.eventloop.pending.tasks").gauge());
    }
}