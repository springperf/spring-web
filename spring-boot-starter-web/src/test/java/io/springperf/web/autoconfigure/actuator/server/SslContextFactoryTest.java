package io.springperf.web.autoconfigure.actuator.server;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.Ssl;
import org.springframework.mock.env.MockEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSL 强类型绑定契约测试：正常流程、非法配置 fail-fast、边界与空值。
 * 使用真实 {@link MockEnvironment} 驱动 {@link org.springframework.boot.context.properties.bind.Binder}，
 * 不再 mock Environment 的字符串读取，以便覆盖 relaxed binding 与枚举转换。
 */
class SslContextFactoryTest {

    private static final String PREFIX = "server.ssl.";
    private static final String CERT = "classpath:ssl/cert.pem";
    private static final String KEY = "classpath:ssl/key.pem";

    private static MockEnvironment pemEnv() {
        return new MockEnvironment().withProperty(PREFIX + "certificate", CERT)
                .withProperty(PREFIX + "certificate-private-key", KEY);
    }

    // ==================== 正常流程 ====================

    @Test
    void createServerSslContext_pemFromClasspath_streamBased() {
        assertNotNull(SslContextFactory.createServerSslContext(pemEnv(), PREFIX),
                "classpath PEM 配置应能成功构建 SslContext");
    }

    @Test
    void createServerSslContext_pkcs12KeyStore_builds() throws Exception {
        File keyStore = generatePkcs12KeyStore();
        try {
            MockEnvironment env = new MockEnvironment().withProperty(PREFIX + "enabled", "true")
                    .withProperty(PREFIX + "key-store", keyStore.getAbsolutePath())
                    .withProperty(PREFIX + "key-store-password", "changeit")
                    .withProperty(PREFIX + "key-store-type", "PKCS12");

            assertNotNull(SslContextFactory.createServerSslContext(env, PREFIX),
                    "PKCS12 密钥库配置应能构建 SslContext");
        } finally {
            keyStore.delete();
        }
    }

    @Test
    void createServerSslContext_clientAuthNeed_builds() {
        MockEnvironment env = pemEnv().withProperty(PREFIX + "client-auth", "need");

        assertNotNull(SslContextFactory.createServerSslContext(env, PREFIX));
    }

    @Test
    void createServerSslContext_clientAuthWant_builds() {
        MockEnvironment env = pemEnv().withProperty(PREFIX + "client-auth", "want");

        assertNotNull(SslContextFactory.createServerSslContext(env, PREFIX));
    }

    @Test
    void createServerSslContext_clientAuthNone_builds() {
        MockEnvironment env = pemEnv().withProperty(PREFIX + "client-auth", "none");

        assertNotNull(SslContextFactory.createServerSslContext(env, PREFIX));
    }

    @Test
    void createServerSslContext_clientAuthNeed_withTrustStore_builds() throws Exception {
        File trustStore = createPkcs12TrustStoreFromCert();
        try {
            MockEnvironment env = pemEnv().withProperty(PREFIX + "client-auth", "need")
                    .withProperty(PREFIX + "trust-store", trustStore.getAbsolutePath())
                    .withProperty(PREFIX + "trust-store-password", "changeit")
                    .withProperty(PREFIX + "trust-store-type", "PKCS12");

            assertNotNull(SslContextFactory.createServerSslContext(env, PREFIX),
                    "client-auth=need + trust-store 应构建支持 mTLS 的 SslContext");
        } finally {
            trustStore.delete();
        }
    }

    @Test
    void createServerSslContext_enabledProtocolsAndCiphers_builds() {
        MockEnvironment env = pemEnv().withProperty(PREFIX + "enabled-protocols", "TLSv1.2,TLSv1.3")
                .withProperty(PREFIX + "ciphers", "TLS_AES_128_GCM_SHA256");

        assertNotNull(SslContextFactory.createServerSslContext(env, PREFIX));
    }

    @Test
    void createServerSslContext_http2Enabled_addsAlpn() {
        assertNotNull(SslContextFactory.createServerSslContext(pemEnv(), PREFIX, true),
                "http2Enabled=true 应配置 ALPN 后构建 SslContext");
    }

    @Test
    void createServerSslContext_stronglyTypedEntryPoint_builds() {
        Ssl ssl = new Ssl();
        ssl.setCertificate(CERT);
        ssl.setCertificatePrivateKey(KEY);

        assertNotNull(SslContextFactory.createServerSslContext(ssl, false),
                "主/管理两侧最终都汇聚到强类型入口");
    }

    // ==================== 异常流程 / 错误输入（fail-fast） ====================

    @Test
    void createServerSslContext_enabledExplicitlyWithoutCertificate_failsFast() {
        MockEnvironment env = new MockEnvironment().withProperty(PREFIX + "enabled", "true");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SslContextFactory.createServerSslContext(env, PREFIX));
        assertTrue(ex.getCause().getMessage().contains("neither certificate"),
                "启用 SSL 却缺少证书/密钥库时必须给出明确原因，而不是空指针");
    }

    @Test
    void createServerSslContext_invalidClientAuth_failsFast() {
        MockEnvironment env = pemEnv().withProperty(PREFIX + "client-auth", "bogus");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SslContextFactory.createServerSslContext(env, PREFIX),
                "非法 client-auth 必须在绑定期失败，不得静默回退 NONE");
        assertTrue(ex.getMessage().contains("server.ssl"), "异常必须带配置前缀上下文");
    }

    @Test
    void createServerSslContext_blankKeyStore_failsFast() {
        MockEnvironment env = new MockEnvironment().withProperty(PREFIX + "key-store", "")
                .withProperty(PREFIX + "key-store-password", "changeit");

        assertThrows(IllegalStateException.class, () -> SslContextFactory.createServerSslContext(env, PREFIX));
    }

    @Test
    void createServerSslContext_missingKeyStoreFile_failsFast() {
        MockEnvironment env = new MockEnvironment().withProperty(PREFIX + "key-store", "no-such-keystore.p12")
                .withProperty(PREFIX + "key-store-password", "changeit");

        assertThrows(IllegalStateException.class, () -> SslContextFactory.createServerSslContext(env, PREFIX));
    }

    // ==================== 边界 / 空值场景 ====================

    @Test
    void createServerSslContext_nullEnv_returnsNull() {
        assertNull(SslContextFactory.createServerSslContext(null, PREFIX));
    }

    @Test
    void createServerSslContext_noSslProperties_returnsNull() {
        assertNull(SslContextFactory.createServerSslContext(new MockEnvironment(), PREFIX));
    }

    @Test
    void createServerSslContext_explicitFalse_beatsKeyStore() throws Exception {
        File keyStore = generatePkcs12KeyStore();
        try {
            MockEnvironment env = new MockEnvironment().withProperty(PREFIX + "enabled", "false")
                    .withProperty(PREFIX + "key-store", keyStore.getAbsolutePath())
                    .withProperty(PREFIX + "key-store-password", "changeit");

            assertNull(SslContextFactory.createServerSslContext(env, PREFIX),
                    "显式 enabled=false 必须关闭 SSL，残留 key-store 不得重新启用");
        } finally {
            keyStore.delete();
        }
    }

    @Test
    void createServerSslContext_nullSslObject_returnsNull() {
        assertNull(SslContextFactory.createServerSslContext((Ssl) null, false));
    }

    @Test
    void createServerSslContext_nonConfigurableEnvironment_staysCompatible() {
        org.springframework.core.env.Environment env = org.mockito.Mockito
                .mock(org.springframework.core.env.Environment.class);

        assertNull(SslContextFactory.createServerSslContext(env, PREFIX),
                "public API 仍接受普通 Environment，Binder 适配层不得抛类型异常");
    }

    @Test
    void openInputStream_classpathResource_returnsReadableStream() throws Exception {
        try (InputStream in = invokeOpenInputStream(CERT)) {
            assertNotNull(in);
            assertTrue(in.available() > 0, "classpath 证书资源必须可读");
        }
    }

    @Test
    void openInputStream_filePath_fallsBackToFileInput() throws Exception {
        File f = File.createTempFile("cert", ".pem");
        try (InputStream src = SslContextFactoryTest.class.getResourceAsStream("/ssl/cert.pem");
                OutputStream os = new FileOutputStream(f)) {
            src.transferTo(os);
        }
        try (InputStream in = invokeOpenInputStream(f.getAbsolutePath())) {
            assertNotNull(in);
            assertTrue(in.available() > 0);
        } finally {
            f.delete();
        }
    }

    @Test
    void openInputStream_nullPath_throwsIllegalArgument() throws Exception {
        java.lang.reflect.InvocationTargetException ex = assertThrows(
                java.lang.reflect.InvocationTargetException.class, () -> invokeOpenInputStream(null));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void binder_acceptsRelaxedPropertyNames() {
        MockEnvironment env = new MockEnvironment().withProperty(PREFIX + "certificate", CERT)
                .withProperty("server.ssl.certificatePrivateKey", KEY);

        assertNotNull(SslContextFactory.createServerSslContext(env, PREFIX),
                "Boot Binder 应支持 camelCase 等 relaxed binding 形式");
    }

    // ==================== 辅助方法 ====================

    private static InputStream invokeOpenInputStream(String path) throws Exception {
        Method m = SslContextFactory.class.getDeclaredMethod("openInputStream", String.class);
        m.setAccessible(true);
        return (InputStream) m.invoke(null, path);
    }

    /** 从 classpath ssl/cert.pem 读取证书并写入临时 PKCS12 信任库。 */
    private static File createPkcs12TrustStoreFromCert() throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream in = SslContextFactoryTest.class.getResourceAsStream("/ssl/cert.pem")) {
            Certificate cert = cf.generateCertificate(in);
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setCertificateEntry("test-ca", cert);
            File f = File.createTempFile("truststore", ".p12");
            f.deleteOnExit();
            try (OutputStream os = new FileOutputStream(f)) {
                ks.store(os, "changeit".toCharArray());
            }
            return f;
        }
    }

    /** 生成含自签密钥对的 PKCS12 密钥库（仅 JDK + keytool，不引入第三方库）。 */
    private static File generatePkcs12KeyStore() throws Exception {
        String keytoolPath = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + (System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        File ksFile = File.createTempFile("keystore", ".p12");
        ksFile.delete();
        ksFile.deleteOnExit();
        Process p = new ProcessBuilder(keytoolPath, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize",
                "2048", "-storetype", "PKCS12", "-keystore", ksFile.getAbsolutePath(), "-storepass", "changeit",
                "-keypass", "changeit", "-dname", "CN=localhost").redirectErrorStream(true).start();
        String output = readAllOutput(p.getInputStream());
        p.waitFor();
        if (p.exitValue() != 0) {
            throw new IllegalStateException("keytool 生成密钥库失败: " + output);
        }
        return ksFile;
    }

    private static String readAllOutput(InputStream in) throws Exception {
        try (in; Scanner sc = new Scanner(in, "UTF-8")) {
            sc.useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : "";
        }
    }
}
