package io.springperf.web.autoconfigure.actuator.server;

import io.springperf.web.context.PropertiesConstant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端口配置快照的契约测试：默认继承 {@code server.*}、白名单显式覆盖、L3 fail-fast、SSL 继承与关闭。
 */
class ManagementServerSettingsTest {

    private static final String CERT = "classpath:ssl/cert.pem";
    private static final String KEY = "classpath:ssl/key.pem";

    private static MockEnvironment managementEnv() {
        return new MockEnvironment().withProperty("management.server.port", "9090");
    }

    private static ManagementServerSettings resolve(MockEnvironment env) {
        return ManagementServerSettings.resolve(env);
    }

    // ==================== 正常流程：默认继承 server.* ====================

    @Test
    void http2_defaultsToServerValue() {
        MockEnvironment env = managementEnv().withProperty("server.http2.enabled", "true");

        assertTrue(resolve(env).getHttp2Enabled());
    }

    @Test
    void maxContentLength_defaultsToServerValue() {
        MockEnvironment env = managementEnv().withProperty("server.http.max-content-length", "9999");

        assertEquals(9999, resolve(env).getMaxContentLength());
    }

    @Test
    void addressAndTransport_defaultToServerValues() {
        MockEnvironment env = managementEnv().withProperty("server.address", "127.0.0.1")
                .withProperty("server.netty.transport", "nio");

        ManagementServerSettings settings = resolve(env);
        assertEquals("127.0.0.1", settings.getBindAddress());
        assertEquals("nio", settings.getTransport());
    }

    @Test
    void protocolLimits_defaultToServerValues() {
        MockEnvironment env = managementEnv().withProperty("server.http.read-timeout", "12s")
                .withProperty("server.http.max-initial-line-length", "2048")
                .withProperty("server.max-connections", "77")
                .withProperty("server.shutdown.grace-period", "5s");

        ManagementServerSettings settings = resolve(env);
        assertEquals(12000L, settings.getReadTimeoutMillis());
        assertEquals(2048, settings.getMaxInitialLineLength());
        assertEquals(77, settings.getMaxConnections());
        assertEquals(5000L, settings.getShutdownGraceMillis());
    }

    @Test
    void ssl_inheritsServerCertificateWhenNoManagementOverride() {
        MockEnvironment env = managementEnv().withProperty("server.ssl.certificate", CERT)
                .withProperty("server.ssl.certificate-private-key", KEY);

        assertNotNull(resolve(env).getSslContext(), "未配置 management.server.ssl.* 时应继承主服务器证书");
    }

    // ==================== 正常流程：management.server.* 显式覆盖 ====================

    @Test
    void http2_managementOverrideWinsOverServer() {
        MockEnvironment env = managementEnv().withProperty("server.http2.enabled", "true")
                .withProperty("management.server.http2.enabled", "false");

        assertFalse(resolve(env).getHttp2Enabled());
    }

    @Test
    void maxContentLength_legacyManagementKeyWins() {
        MockEnvironment env = managementEnv().withProperty("server.http.max-content-length", "9999")
                .withProperty("management.server.max-content-length", "2048");

        assertEquals(2048, resolve(env).getMaxContentLength());
    }

    @Test
    void maxContentLength_mirroredManagementKeyWins() {
        MockEnvironment env = managementEnv().withProperty("server.http.max-content-length", "9999")
                .withProperty("management.server.http.max-content-length", "4096");

        assertEquals(4096, resolve(env).getMaxContentLength());
    }

    @Test
    void addressAndTransport_managementOverridesWin() {
        MockEnvironment env = managementEnv().withProperty("server.address", "127.0.0.1")
                .withProperty("server.netty.transport", "epoll")
                .withProperty("management.server.address", "0.0.0.0")
                .withProperty("management.server.netty.transport", "nio");

        ManagementServerSettings settings = resolve(env);
        assertEquals("0.0.0.0", settings.getBindAddress());
        assertEquals("nio", settings.getTransport());
    }

    @Test
    void protocolLimits_managementOverridesWin() {
        MockEnvironment env = managementEnv().withProperty("server.http.read-timeout", "12s")
                .withProperty("server.max-connections", "77")
                .withProperty("management.server.http.read-timeout", "3s")
                .withProperty("management.server.max-connections", "5");

        ManagementServerSettings settings = resolve(env);
        assertEquals(3000L, settings.getReadTimeoutMillis());
        assertEquals(5, settings.getMaxConnections());
    }

    @Test
    void ssl_managementCertificateOverrides() {
        MockEnvironment env = managementEnv().withProperty("management.server.ssl.certificate", CERT)
                .withProperty("management.server.ssl.certificate-private-key", KEY);

        assertNotNull(resolve(env).getSslContext());
    }

    @Test
    void ssl_managementEnableOverridesInheritedDisabled() {
        MockEnvironment env = managementEnv().withProperty("server.ssl.enabled", "false")
                .withProperty("server.ssl.certificate", CERT)
                .withProperty("server.ssl.certificate-private-key", KEY)
                .withProperty("management.server.ssl.enabled", "true");

        assertNotNull(resolve(env).getSslContext(), "主服务器关闭 SSL 时，管理侧可显式 enabled=true 独立启用");
    }

    @Test
    void ssl_serverEnabledFalseIsInherited() {
        MockEnvironment env = managementEnv().withProperty("server.ssl.enabled", "false")
                .withProperty("server.ssl.certificate", CERT)
                .withProperty("server.ssl.certificate-private-key", KEY);

        assertNull(resolve(env).getSslContext(), "未配置管理侧开关时应继承主服务器的 enabled=false");
    }

    @Test
    void compression_managementGroupOverrideWins() {
        MockEnvironment env = managementEnv().withProperty("server.compression.enabled", "false")
                .withProperty("management.server.compression.enabled", "true");

        assertTrue(resolve(env).getCompression().isEnabled(), "compression.* 整组允许覆盖");
    }

    @Test
    void ssl_explicitDisabledBeatsInheritedCertificate() {
        MockEnvironment env = managementEnv().withProperty("server.ssl.certificate", CERT)
                .withProperty("server.ssl.certificate-private-key", KEY)
                .withProperty("management.server.ssl.enabled", "false");

        assertNull(resolve(env).getSslContext(), "显式 enabled=false 必须关闭 SSL，即使继承了主服务器证书");
    }

    // ==================== 异常流程 / 错误输入 ====================

    @Test
    void l3Override_failsFastForSharedWebContextKey() {
        MockEnvironment env = managementEnv().withProperty("management.server.http.timeout", "1000");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> resolve(env));
        assertTrue(ex.getMessage().contains("management.server.http.timeout"), "报错必须点明具体配置键");
    }

    @Test
    void l3Override_failsFastForServletContextPath() {
        MockEnvironment env = managementEnv().withProperty("management.server.servlet.context-path", "/mgmt");

        assertThrows(IllegalStateException.class, () -> resolve(env));
    }

    @Test
    void l3Override_failsFastForUnusedNettyKey() {
        MockEnvironment env = managementEnv().withProperty("management.server.netty.workers", "4");

        assertThrows(IllegalStateException.class, () -> resolve(env));
    }

    @Test
    void invalidPort_failsFastAtStartup() {
        MockEnvironment env = managementEnv().withProperty("management.server.port", "not-a-number");

        assertThrows(RuntimeException.class, () -> resolve(env), "非法端口必须在启动期暴露，不得静默降级");
    }

    @Test
    void nullEnvironment_failsFast() {
        assertThrows(IllegalArgumentException.class, () -> ManagementServerSettings.resolve(null));
    }

    // ==================== 边界 / 空值场景 ====================

    @Test
    void bootOwnManagementKeys_areNotRejected() {
        MockEnvironment env = managementEnv().withProperty("management.server.add-application-context-header", "false");

        assertNotNull(resolve(env));
    }

    @Test
    void nestedSslKeys_areAllowedAsWholeGroup() {
        MockEnvironment env = managementEnv().withProperty("management.server.ssl.client-auth", "need")
                .withProperty("management.server.ssl.enabled-protocols", "TLSv1.3");

        assertNotNull(resolve(env));
    }

    @Test
    void emptyEnvironment_usesFrameworkDefaults() {
        ManagementServerSettings settings = resolve(managementEnv());

        assertEquals(9090, settings.getPort());
        assertFalse(settings.getHttp2Enabled());
        assertNull(settings.getSslContext());
        assertNull(settings.getBindAddress());
        assertEquals(PropertiesConstant.HTTP_MAX_CONTENT_LENGTH_DEFAULT, settings.getMaxContentLength());
        assertTrue(settings.getMaxInitialLineLength() > 0);
    }

    @Test
    void zeroPort_isPreservedForRandomBinding() {
        MockEnvironment env = managementEnv().withProperty("management.server.port", "0");

        assertEquals(0, resolve(env).getPort());
    }

    @Test
    void fromApplicationProperties_nullPropsFallsBackToDefaults() {
        ManagementServerSettings settings = ManagementServerSettings.fromApplicationProperties(null, 9099, 2048, null);

        assertEquals(9099, settings.getPort());
        assertEquals(2048, settings.getMaxContentLength());
        assertFalse(settings.getHttp2Enabled());
    }
}
