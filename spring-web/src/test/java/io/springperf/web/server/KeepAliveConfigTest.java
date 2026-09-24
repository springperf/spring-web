package io.springperf.web.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import io.springperf.web.context.ApplicationProperties;

class KeepAliveConfigTest {

    @Test
    void defaults_maxRequests100_timeoutDisabled() {
        ApplicationProperties props = new ApplicationProperties();
        props.setEnvironment(new MockEnvironment());
        KeepAliveConfig cfg = KeepAliveConfig.fromProperties(props);
        assertThat(cfg.getMaxRequests()).isEqualTo(100);
        assertThat(cfg.getTimeoutMillis()).isEqualTo(0L);
        assertThat(cfg.isEnabled()).isTrue();
    }

    @Test
    void disabled_isNotEnabled() {
        assertThat(KeepAliveConfig.DISABLED.isEnabled()).isFalse();
    }

    @Test
    void custom_parsed() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("server.keep-alive-timeout", "30s");
        env.setProperty("server.max-keep-alive-requests", "5");
        ApplicationProperties props = new ApplicationProperties();
        props.setEnvironment(env);
        KeepAliveConfig cfg = KeepAliveConfig.fromProperties(props);
        assertThat(cfg.getTimeoutMillis()).isEqualTo(30000L);
        assertThat(cfg.getMaxRequests()).isEqualTo(5);
    }
}
