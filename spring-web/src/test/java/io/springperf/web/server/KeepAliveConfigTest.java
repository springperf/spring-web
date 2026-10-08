package io.springperf.web.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import io.springperf.web.context.ApplicationProperties;

class KeepAliveConfigTest {

    /**
     * 默认**两个维度都关闭** ⇒ handler 不注入管线（{@code Http2ChannelInitializer.addKeepAlive} 由 {@code isEnabled()} 守卫）。
     * 这是本项目的策略选择：默认不施加连接级隐式限制；键名与 Boot 相同，默认值不同（Boot/Tomcat 为 100）。
     */
    @Test
    void defaults_bothDisabled_handlerNotInstalled() {
        ApplicationProperties props = new ApplicationProperties();
        props.setEnvironment(new MockEnvironment());
        KeepAliveConfig cfg = KeepAliveConfig.fromProperties(props);
        assertThat(cfg.getMaxRequests()).isEqualTo(0);
        assertThat(cfg.getTimeoutMillis()).isEqualTo(0L);
        assertThat(cfg.isEnabled()).isFalse();
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
